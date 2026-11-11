package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/** The people's claims on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /**
     * A claim as shown: the amount with two decimals, the date as yyyy-mm-dd, and the due fixed when it was
     * settled (null when it has none, so its due is worked out from the current rate), and who opened it;
     * whether the person's last statement bound it, and the due it bound it to (null: none, so n/a); and the number of
     * the claim it corrects (null unless it is a correction); and the date that decides how its live figures round (its
     * own, or a correction's corrected claim's); and its place in its person's list (a later place listed first); and
     * its status log, oldest first; and the date it is deferred until (null when never deferred); and how many proofs
     * it has; and the number of the claim it was split off (null unless it was); and the user whose open editor locks
     * it (null when not locked); and the last day it was reminded (null when never); and its external reference (null
     * when it has none); and its tags, sorted and comma separated (null when it has none); and the reason it was voided
     * with (null when none); and its comments, oldest first; and the bulk settle batch that settled it (null when none).
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects,
            LocalDate roundingDate, long listed, LocalDate dueBy, List<ClaimLogView> log, LocalDate deferredUntil,
            int proofs, String splitFrom, String lockedBy, boolean lateEntry, LocalDate remindedOn, String ref,
            String tags, String voidReason, List<ClaimCommentView> comments, Long settleBatch,
            List<ClaimLineView> lines) {

        /** A claim with no comments or lines yet. */
        static ClaimView of(Claim claim, List<ClaimLog> log) {
            return of(claim, log, List.of(), List.of());
        }

        static ClaimView of(Claim claim, List<ClaimLog> log, List<ClaimComment> comments, List<ClaimLine> lines) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects(), claim.getRoundingDate(), claim.getListed(), claim.getDueBy(),
                    log.stream().map(ClaimLogView::of).toList(), claim.getDeferredUntil(),
                    claim.getProofs(), claim.getSplitFrom(), claim.getLockedBy(),
                    claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(),
                    claim.getTags(), claim.getVoidReason(),
                    comments.stream().map(ClaimCommentView::of).toList(), claim.getSettleBatch(),
                    lines.stream().map(ClaimLineView::of).toList());
        }
    }

    /** A line of a claim: its label, its quantity and its unit amount with two decimals. */
    public record ClaimLineView(String label, int qty, String unit) {

        static ClaimLineView of(ClaimLine line) {
            return new ClaimLineView(line.getLabel(), line.getQty(),
                    line.getUnit().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record ClaimCommentView(String by, LocalDate date, String text) {

        static ClaimCommentView of(ClaimComment comment) {
            return new ClaimCommentView(comment.getBy(), comment.getDate(), comment.getText());
        }
    }

    /** A line of a claim's status log: the status, who changed it, the sheet's today then, and its due then (or n/a). */
    public record ClaimLogView(ClaimStatus status, String by, LocalDate date, String due) {

        static ClaimLogView of(ClaimLog line) {
            return new ClaimLogView(line.getStatus(), line.getBy(), line.getDate(), line.getDue());
        }
    }

    /**
     * An amount with up to two decimals, negative only for a correction; a missing date takes the sheet's today; the
     * number of the claim a correction corrects, blank for any other claim; an external reference, blank for none;
     * comma separated tags, blank for none.
     */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 255) String corrects,
            @Size(max = 255) String ref,
            @Size(max = 1000) String tags) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }

        String correctsValue() {
            if (corrects == null || corrects.isBlank()) {
                return null;
            }
            // A sheet-wide number is written without leading zeros; a period number (P1-1, X-1) as typed.
            String trimmed = corrects.trim();
            return trimmed.matches("\\d{1,18}") ? String.valueOf(Long.parseLong(trimmed)) : trimmed;
        }
    }

    /** The ids of the claims selected to open together. */
    public record BulkOpenRequest(@NotNull @Size(max = 10000) List<@NotNull Long> ids) {
    }

    /** The ids of the claims selected to move together, and the person they are moved to. */
    public record BulkReassignRequest(@NotNull @Size(max = 10000) List<@NotNull Long> ids, @NotNull Long personId) {
    }

    /** The ids of the claims selected to re-date together, and the date they are re-dated to. */
    public record BulkRedateRequest(@NotNull @Size(max = 10000) List<@NotNull Long> ids, @NotNull LocalDate date) {
    }

    /** The ids of the claims selected to settle together. */
    public record BulkSettleRequest(@NotNull @Size(max = 10000) List<@NotNull Long> ids) {
    }

    /** Lines of name,amount,date,note to add as DRAFT claims. */
    public record PasteRequest(@NotNull @Size(max = 1000000) String text) {
    }

    /** How many claims a paste added. */
    public record PasteResult(int added) {
    }

    private final ClaimService service;
    private final BulkOpenService bulkOpen;
    private final BulkSettleService bulkSettle;
    private final PasteClaimsService paste;
    private final BulkReassignService bulkReassign;
    private final BulkRedateService bulkRedate;

    public ClaimController(ClaimService service, BulkOpenService bulkOpen, BulkSettleService bulkSettle,
            PasteClaimsService paste, BulkReassignService bulkReassign, BulkRedateService bulkRedate) {
        this.bulkReassign = bulkReassign;
        this.bulkRedate = bulkRedate;
        this.service = service;
        this.bulkOpen = bulkOpen;
        this.bulkSettle = bulkSettle;
        this.paste = paste;
    }

    /** All the sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, List<ClaimLog>> logs = service.logs(sheetId.trim());
        Map<Long, List<ClaimComment>> comments = service.comments(sheetId.trim());
        Map<Long, List<ClaimLine>> lines = service.lines(sheetId.trim());
        return service.list(sheetId.trim()).stream()
                .map(claim -> ClaimView.of(claim, logs.getOrDefault(claim.getId(), List.of()),
                        comments.getOrDefault(claim.getId(), List.of()),
                        lines.getOrDefault(claim.getId(), List.of())))
                .toList();
    }

    /** Every tag ever used on the sheet, sorted. */
    @GetMapping("/claims/tags")
    public List<String> tags(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.tags(sheetId.trim());
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.date(),
                request.note(), request.correctsValue(), request.ref(), request.tags()));
    }

    /** Add a DRAFT claim per pasted line, in order; refused as a whole when any line would be. */
    @PostMapping("/claims/paste")
    public PasteResult paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PasteRequest request) {
        return new PasteResult(paste.paste(sheetId.trim(), user.trim(), request.text()));
    }

    /** Edit a DRAFT claim; refused for any other status. */
    @PutMapping("/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.update(sheetId.trim(), user.trim(), id, request.amountValue(), request.date(),
                request.note(), request.correctsValue(), request.ref(), request.tags()));
    }

    /** Open the claim's editor, locking it against other users' edits; refused for a claim that cannot be edited. */
    @PostMapping("/claims/{id}/lock")
    public ClaimView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.lock(sheetId.trim(), user.trim(), id));
    }

    /** Cancel the user's editor of the claim, releasing its lock. */
    @PostMapping("/claims/{id}/unlock")
    public ClaimView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.unlock(sheetId.trim(), user.trim(), id));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.OPEN));
    }

    /** Review a REVIEW claim to OPEN: a steward only. */
    @PostMapping("/claims/{id}/review")
    public ClaimView review(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.review(sheetId.trim(), user.trim(), id));
    }

    /** Open the selected claims in list order; each one that cannot be opened is left as it is. */
    @PostMapping("/claims/bulk-open")
    public BulkOpenService.Result bulkOpen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkOpenRequest request) {
        return bulkOpen.open(sheetId.trim(), user.trim(), request.ids());
    }

    /** Settle the selected claims in list order; each one that cannot be settled is left as it is. */
    @PostMapping("/claims/bulk-settle")
    public BulkSettleService.Result bulkSettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkSettleRequest request) {
        return bulkSettle.settle(sheetId.trim(), user.trim(), request.ids());
    }

    /** Settle an OPEN claim; refused to the user who opened or last edited it; PENDING instead when a clerk settles it above the clerk limit. */
    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.SETTLED));
    }

    /** Re-settle a SETTLED claim at its figure as of today: a steward only. */
    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.resettle(sheetId.trim(), user.trim(), id));
    }

    /** A steward's reason for rejecting a PENDING claim. */
    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    /** Approve a PENDING claim: a steward only, and not the one who opened or last edited it. */
    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.approve(sheetId.trim(), user.trim(), id));
    }

    /** Reject a PENDING claim with a reason, back to OPEN: a steward only. */
    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RejectRequest request) {
        // One line in the audit file: any run of white space in the reason becomes a single space.
        String reason = request.reason().trim().replaceAll("\\s+", " ");
        return view(service.reject(sheetId.trim(), user.trim(), id, reason));
    }

    /** Move the selected claims to a person in list order; each one that cannot be moved is left as it is. */
    @PostMapping("/claims/bulk-reassign")
    public BulkReassignService.Result bulkReassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkReassignRequest request) {
        return bulkReassign.reassign(sheetId.trim(), user.trim(), request.ids(), request.personId());
    }

    /** Re-date the selected claims in list order; each one that cannot be re-dated is left as it is. */
    @PostMapping("/claims/bulk-redate")
    public BulkRedateService.Result bulkRedate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRedateRequest request) {
        return bulkRedate.redate(sheetId.trim(), user.trim(), request.ids(), request.date());
    }

    /** The person a claim is moved to. */
    public record ReassignRequest(@NotNull Long personId) {
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person: a role the permission matrix allows to reassign. */
    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReassignRequest request) {
        return view(service.reassign(sheetId.trim(), user.trim(), id, request.personId()));
    }

    /** The date an OPEN claim is deferred until; it must be after the sheet's today. */
    public record DeferRequest(@NotNull LocalDate until) {
    }

    /** Defer an OPEN claim until a date after the sheet's today. */
    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DeferRequest request) {
        return view(service.defer(sheetId.trim(), user.trim(), id, request.until()));
    }

    /** The new date of a claim re-dated. */
    public record RedateRequest(@NotNull LocalDate date) {
    }

    /** Re-date an OPEN or PENDING claim: a steward only. */
    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RedateRequest request) {
        return view(service.redate(sheetId.trim(), user.trim(), id, request.date()));
    }

    /** The amount split off a claim: more than 0.00 and less than the claim's amount. */
    public record SplitRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** Split an amount off an OPEN claim into a new OPEN claim of the same person: the new claim. */
    @PostMapping("/claims/{id}/split")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView split(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SplitRequest request) {
        return view(service.split(sheetId.trim(), user.trim(), id, request.amountValue()));
    }

    /** Add a proof to a claim, raising its proofs count by one; refused for a SETTLED or VOID claim. */
    @PostMapping("/claims/{id}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.addProof(sheetId.trim(), user.trim(), id));
    }

    /** Remind an OPEN or PENDING claim, at most once per sheet-today. */
    @PostMapping("/claims/{id}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.remind(sheetId.trim(), user.trim(), id));
    }

    /** The reason a claim is voided with, from the sheet's list of void reasons; blank or missing for none. */
    public record VoidRequest(@Size(max = 255) String reason) {
    }

    /**
     * Void a DRAFT, OPEN or PENDING claim, or (a steward, within 7 days of its settlement) a SETTLED one; while the
     * sheet has void reasons, with one of them.
     */
    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) @Valid VoidRequest request) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.VOID,
                request == null ? null : request.reason()));
    }

    /** Delete a DRAFT claim. */
    @PostMapping("/claims/{id}/delete")
    public ClaimView delete(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.delete(sheetId.trim(), user.trim(), id));
    }

    /** Restore a DELETED claim to DRAFT, on the sheet's today it was deleted. */
    @PostMapping("/claims/{id}/restore")
    public ClaimView restore(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.restore(sheetId.trim(), user.trim(), id));
    }

    /** The text of a comment on a claim: not blank. */
    public record CommentRequest(@NotNull @Size(max = 1000) String text) {
    }

    /** Comment a claim of any status; refused for an empty comment. */
    @PostMapping("/claims/{id}/comments")
    public ClaimView comment(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CommentRequest request) {
        // One line in the audit file: any run of white space in the comment becomes a single space.
        String text = request.text().trim().replaceAll("\\s+", " ");
        return view(service.comment(sheetId.trim(), user.trim(), id, text));
    }

    /**
     * A line of a claim: a label, a quantity (a whole number, at least 1) and a unit amount with up to two decimals.
     */
    public record LineRequest(
            @NotNull @Size(max = 255) String label,
            @NotNull @Pattern(regexp = "\\s*0*[1-9]\\d{0,5}\\s*") String qty,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String unit) {

        int qtyValue() {
            return Integer.parseInt(qty.trim());
        }

        BigDecimal unitValue() {
            return new BigDecimal(unit.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** Add a line to a DRAFT claim, its amount then its lines added up; refused as an edit of the claim would be. */
    @PostMapping("/claims/{id}/lines")
    public ClaimView addLine(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LineRequest request) {
        // One line in the audit file: any run of white space in the label becomes a single space.
        String label = request.label().trim().replaceAll("\\s+", " ");
        return view(service.addLine(sheetId.trim(), user.trim(), id, label, request.qtyValue(),
                request.unitValue()));
    }

    /** The claim as shown, with its status log, comments and lines. */
    ClaimView view(Claim claim) {
        return ClaimView.of(claim, service.logOf(claim), service.commentsOf(claim), service.linesOf(claim));
    }
}
