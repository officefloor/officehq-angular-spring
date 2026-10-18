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
     * stated, with the figure it had then (null: n/a).
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, Integer number, String due, String openedBy, boolean stated, String statedDue) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getDue() == null ? null : claim.getDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString());
        }
    }

    /** The amount is 0.00 or more with up to two decimals; the date defaults to the sheet's today. */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
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
        return ClaimView.of(service.add(sheetId, user, personId, form.amountValue(), form.date(), form.note()));
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
        return ClaimView.of(service.edit(sheetId, user, claimId, form.amountValue(), form.date(), form.note()));
    }

    /**
     * Open, settle or void a claim; a change its status does not allow is refused (409), as is
     * settling it by the user who opened it (403).
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
}
