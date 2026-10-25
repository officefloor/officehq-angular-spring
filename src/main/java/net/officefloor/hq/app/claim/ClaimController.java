package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** What the screen shows of a claim; the amount always has two decimals. The settled due is the
     *  due fixed when the claim was settled, or null when its due is live. Opened by is who opened
     *  the claim, or null when it was never opened. Stated is whether the person's last statement bound
     *  the claim; stated due is the figure it bound it to, or null when unbound or n/a then. Corrects is
     *  the number of the claim a correction corrects, or null when the claim is not a correction. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue,
            String openedBy, boolean stated, String statedDue, String corrects, LocalDate dueBy) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects(), claim.getDueBy());
        }
    }

    /** A claim's fields, as entered on the form; no date means the sheet's today. A negative amount
     *  is a correction, which names the number of the settled claim it corrects. */
    public record ClaimForm(
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 220) String corrects) {
    }

    /** The number a form's correction names, trimmed; none when blank. */
    private static String correctsOf(ClaimForm form) {
        return form.corrects() == null || form.corrects().isBlank() ? null : form.corrects().trim();
    }

    /** The claims selected to open together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    /** What a bulk open did: the claims it opened, and how many were opened and refused. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    private final ClaimService service;
    private final BulkOpenService bulkOpen;

    public ClaimController(ClaimService service, BulkOpenService bulkOpen) {
        this.service = service;
        this.bulkOpen = bulkOpen;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    /** Add a claim to a person. */
    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.add(sheetId.trim(), SignedInUser.name(user), personId,
                form.amount(), form.date(), form.note(), correctsOf(form)));
    }

    /** Edit a claim. */
    @PutMapping("/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.update(sheetId.trim(), SignedInUser.name(user), id,
                form.amount(), form.date(), form.note(), correctsOf(form)));
    }

    /** Open a draft claim. */
    @PostMapping("/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.OPEN));
    }

    /** Open the selected claims together in list order; each that cannot be opened is left as it is. */
    @PostMapping("/claims/open")
    public BulkOpenView openSelected(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkOpenForm form) {
        BulkOpenService.Result result = bulkOpen.open(sheetId.trim(), SignedInUser.name(user), form.ids());
        return new BulkOpenView(result.opened().size(), result.refused(),
                result.opened().stream().map(ClaimView::of).toList());
    }

    /** Settle an open claim; a clerk's settling above the clerk limit leaves it pending. */
    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.SETTLED));
    }

    /** Why a steward rejects a pending claim. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    /** Approve a pending claim, settling it. */
    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.approve(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Reject a pending claim for a reason, opening it again. */
    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RejectForm form) {
        return ClaimView.of(service.reject(sheetId.trim(), SignedInUser.name(user), id, form.reason().trim()));
    }

    /** The person a claim is moved to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** Move a draft, open or pending claim to another person, as their newest claim; a steward's change. */
    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ReassignForm form) {
        return ClaimView.of(service.reassign(sheetId.trim(), SignedInUser.name(user), id, form.personId()));
    }

    /** Void a draft or open (or pending) claim. */
    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.VOID));
    }
}
