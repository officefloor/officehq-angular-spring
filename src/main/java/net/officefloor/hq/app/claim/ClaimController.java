package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
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
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /**
     * What the screen is told about a claim; the amount with two decimals, and the due fixed when it
     * was settled (null when not fixed, so its due is worked out from the current rate), and who
     * opened it (null until opened), and whether it is bound by its person's last statement, with the
     * figure it was bound to (null when unbound, or when it was n/a), and the number of the claim it
     * corrects (null unless it is a correction), and the date it is due by, fixed when it was added, and its status
     * log, oldest first.
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, LocalDate dueBy, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects, List<LogLineView> log) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getDueBy(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects(), claim.getLog().stream().map(LogLineView::of).toList());
        }
    }

    /** A line of a claim's status log; the due with two decimals, or n/a. */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {

        public static LogLineView of(ClaimLogLine line) {
            return new LogLineView(line.getStatus(), line.getBy(), line.getOn(),
                    line.getDue() == null ? "n/a" : line.getDue().toPlainString());
        }
    }

    /**
     * A claim's values as entered on the form: the amount is required, with up to two decimals, and
     * below 0.00 only on a correction, which names the number of the claim it corrects; no date means
     * the sheet's today (or, on an edit, the claim's own); the note may be empty.
     */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Pattern(regexp = "[1-9]\\d{0,8}|\\S.{0,200}-[1-9]\\d{0,8}") String corrects) {
    }

    /** Why a steward rejects a PENDING claim. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    /** The person a claim is moved to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** What a bulk open did: the claims opened, as they are now, and how many were refused. */
    public record BulkOpenView(List<ClaimView> opened, int refused) {
    }

    /** The claims selected to be settled together. */
    public record BulkSettleForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** What a bulk settle did: the claims settled (or sent PENDING), as they are now, and how many were refused. */
    public record BulkSettleView(List<ClaimView> settled, int refused) {
    }

    private final ClaimService service;
    private final BulkMoveService bulk;

    public ClaimController(ClaimService service, BulkMoveService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.add(sheetId, SignedIn.name(user), personId, new BigDecimal(form.amount()), form.date(), form.note(), form.corrects()));
    }

    /** Change a claim's amount, date and note. */
    @PutMapping("/claims/{claimId}")
    public ClaimView edit(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.edit(sheetId, SignedIn.name(user), claimId, new BigDecimal(form.amount()), form.date(), form.note(), form.corrects()));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.OPEN));
    }

    /** Open the selected claims together, in list order; those that cannot be opened are refused. */
    @PostMapping("/claims/open")
    public BulkOpenView openAll(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkOpenForm form) {
        BulkMoveService.Result result = bulk.open(sheetId, SignedIn.name(user), form.claimIds());
        return new BulkOpenView(result.moved().stream().map(ClaimView::of).toList(), result.refused());
    }

    /** Settle an OPEN claim (PENDING when a clerk settles it above the clerk limit); refused for the user who opened it. */
    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.SETTLED));
    }

    /** Settle the selected claims together, in list order; those that cannot be settled are refused. */
    @PostMapping("/claims/settle")
    public BulkSettleView settleAll(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkSettleForm form) {
        BulkMoveService.Result result = bulk.settle(sheetId, SignedIn.name(user), form.claimIds());
        return new BulkSettleView(result.moved().stream().map(ClaimView::of).toList(), result.refused());
    }

    /** Approve a PENDING claim, settling it; only a steward, and not the user who opened it. */
    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.approve(sheetId, SignedIn.name(user), claimId));
    }

    /** Re-settle a SETTLED claim at its figure today; only a steward. */
    @PostMapping("/claims/{claimId}/resettle")
    public ClaimView resettle(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.resettle(sheetId, SignedIn.name(user), claimId));
    }

    /** Reject a PENDING claim with a reason, back to OPEN; only a steward. */
    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RejectForm form) {
        return ClaimView.of(service.reject(sheetId, SignedIn.name(user), claimId, form.reason()));
    }

    /** Void a DRAFT, OPEN or PENDING claim. */
    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.VOID));
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet; only a steward. */
    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ReassignForm form) {
        return ClaimView.of(service.reassign(sheetId, SignedIn.name(user), claimId, form.personId()));
    }
}
