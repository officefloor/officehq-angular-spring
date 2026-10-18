package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim; the amount (and a settled claim's fixed due, null otherwise) is sent as text with two
     * decimals so it is never rounded on the way. A claim bound by its person's last statement is
     * stated, with the figure it had then (null: n/a). Its due-by was fixed when it was created.
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, LocalDate dueBy, String note,
            ClaimStatus status, String number, String due, String openedBy, boolean stated, String statedDue,
            String corrects, List<LogLine> log) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getDueBy(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getDue() == null ? null : claim.getDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString(),
                    claim.getCorrects(), claim.getLog().stream().map(LogLine::of).toList());
        }
    }

    /** A line of a claim's status log; its due as text with two decimals (null: n/a). */
    public record LogLine(ClaimStatus status, String by, LocalDate on, String due) {
        static LogLine of(ClaimLogEntry entry) {
            return new LogLine(entry.getStatus(), entry.getBy(), entry.getOn(),
                    entry.getDue() == null ? null : entry.getDue().setScale(2).toPlainString());
        }
    }

    /**
     * The amount has up to two decimals; a negative one (a correction) names, in {@code corrects}, the
     * number of the settled claim of the same person it corrects. The date defaults to the sheet's today.
     */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 220) String corrects) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }

        String correctsValue() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(sheetId, user, personId, form.amountValue(), form.date(), form.note(),
                form.correctsValue()));
    }

    /**
     * Take a statement for a person: record their due-total and bind each claim of theirs that counts
     * to its figure now (the others are left unbound). Their claims, newest first.
     */
    @PostMapping("/people/{personId}/statement")
    public List<ClaimView> statement(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.statement(sheetId, user, personId).stream().map(ClaimView::of).toList();
    }

    /** Change a draft claim's amount, date and note; any other claim is refused (409). */
    @PutMapping("/claims/{claimId}")
    public ClaimView edit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.edit(sheetId, user, claimId, form.amountValue(), form.date(), form.note(),
                form.correctsValue()));
    }

    /** The claims a bulk open asks for. */
    public record BulkOpenForm(@NotNull @Size(max = 10000) List<@NotNull Long> claimIds) {
    }

    /**
     * Open the chosen claims that can be opened, numbered in list order; the others are left as they
     * are. How many were opened and how many refused.
     */
    @PostMapping("/claims/bulk-open")
    public ClaimService.BulkOpened bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody BulkOpenForm form) {
        return service.bulkOpen(sheetId, user, form.claimIds());
    }

    /**
     * Open, settle or void a claim; a change its status does not allow is refused (409), as is
     * settling it by the user who opened it (403). A clerk's settle above the clerk limit makes it
     * PENDING.
     */
    @PostMapping("/claims/{claimId}/{action:open|settle|void}")
    public ClaimView changeStatus(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long claimId,
            @PathVariable String action,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        ClaimStatus next = switch (action) {
            case "open" -> ClaimStatus.OPEN;
            case "settle" -> ClaimStatus.SETTLED;
            default -> ClaimStatus.VOID;
        };
        return ClaimView.of(service.changeStatus(sheetId, user, claimId, next));
    }

    /** Approve a pending claim (a steward who did not open it): it is settled. */
    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return ClaimView.of(service.approve(sheetId, user, claimId));
    }

    /** Why a pending claim is rejected. */
    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    /** Reject a pending claim with a reason (a steward): it is open again. */
    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody RejectForm form) {
        return ClaimView.of(service.reject(sheetId, user, claimId, form.reason().trim()));
    }

    /** The person a claim moves to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /**
     * Move a draft, open or pending claim to another person (a steward): it keeps its number and date
     * and is live again until its new person's next statement.
     */
    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody ReassignForm form) {
        return ClaimView.of(service.reassign(sheetId, user, claimId, form.personId()));
    }
}
