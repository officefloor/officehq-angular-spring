package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
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
     *  the number of the claim a correction corrects, or null when the claim is not a correction. The log is
     *  its status log, oldest first. Deferred until is the date the claim was last deferred until, or null when
     *  it never was; it is deferred while it is OPEN and the sheet's today is before that date. Proofs is
     *  the claim's proofs count. Split from is the number of the claim it was split off from, or null.
     *  Reminded is the last day a reminder was written for the claim, or null when it never was. Ref is its
     *  external reference, or null when it has none. Tags are its tags, sorted; empty when it has none.
     *  Void reason is the reason it was voided with, or null. Comments are its comments, oldest first.
     *  Lines are its lines, in the order they were added. Edited by is who last edited it, or null when
     *  it never was. Settle batch is the number of the bulk settle batch that settled it, or null when it
     *  was settled one at a time or never. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue,
            String openedBy, boolean stated, String statedDue, String corrects, LocalDate dueBy,
            List<LogLineView> log, LocalDate deferredUntil, int proofs, String splitFrom,
            boolean lateEntry, LocalDate reminded, String ref, List<String> tags, String voidReason,
            List<CommentView> comments, String editedBy, Integer settleBatch, List<LineView> lines) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects(), claim.getDueBy(), claim.getLog().stream().map(LogLineView::of).toList(),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(),
                    claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(),
                    claim.getTags(), claim.getVoidReason(),
                    claim.getComments().stream().map(CommentView::of).toList(), claim.getEditedBy(),
                    claim.getSettleBatch(), claim.getLines().stream().map(LineView::of).toList());
        }
    }

    /** A line of a claim's status log; the due has two decimals, or is null when it was n/a. */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        public static LogLineView of(ClaimLogLine line) {
            return new LogLineView(line.getStatus(), line.getBy(), line.getOn(),
                    line.getDue() == null ? null : line.getDue().toPlainString());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record CommentView(String by, LocalDate on, String text) {
        public static CommentView of(ClaimComment comment) {
            return new CommentView(comment.getBy(), comment.getOn(), comment.getText());
        }
    }

    /** A line of a claim: its label, quantity and unit amount (two decimals). */
    public record LineView(String label, int qty, String unit) {
        public static LineView of(ClaimLine line) {
            return new LineView(line.getLabel(), line.getQty(), line.getUnit().toPlainString());
        }
    }

    /** A claim's fields, as entered on the form; no date means the sheet's today. A negative amount
     *  is a correction, which names the number of the settled claim it corrects. */
    public record ClaimForm(
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 220) String corrects,
            @Size(max = 220) String ref,
            @Size(max = 1000) String tags) {
    }

    /** The number a form's correction names, trimmed; none when blank. */
    private static String correctsOf(ClaimForm form) {
        return form.corrects() == null || form.corrects().isBlank() ? null : form.corrects().trim();
    }

    /** The claims selected to open or settle together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    /** What a bulk open did: the claims it opened, and how many were opened and refused. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    /** What a bulk settle did: the claims it settled (or left pending), how many were settled and refused,
     *  and the number of the batch it made, or null when it settled none. */
    public record BulkSettleView(int settled, int refused, List<ClaimView> claims, Integer batch) {
    }

    /** The claims selected to move together, and the person they move to. */
    public record BulkReassignForm(@NotNull @Size(max = 1000) List<@NotNull Long> ids, @NotNull Long personId) {
    }

    /** What a bulk reassign did: the claims it moved, and how many were moved and refused. */
    public record BulkReassignView(int reassigned, int refused, List<ClaimView> claims) {
    }

    /** The claims selected to re-date together, and their new date. */
    public record BulkRedateForm(@NotNull @Size(max = 1000) List<@NotNull Long> ids, @NotNull LocalDate date) {
    }

    /** What a bulk re-date did: the claims it re-dated, and how many were re-dated and refused. */
    public record BulkRedateView(int redated, int refused, List<ClaimView> claims) {
    }

    private final ClaimService service;
    private final BulkChangeService bulk;

    public ClaimController(ClaimService service, BulkChangeService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    /** The claim's timeline: the audit lines about it, newest first. */
    @GetMapping("/claims/{id}/timeline")
    public List<String> timeline(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id) {
        return service.timeline(sheetId.trim(), id);
    }

    /** Every tag ever used on the sheet, by name. */
    @GetMapping("/tags")
    public List<String> tags(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.tags(sheetId.trim());
    }

    /** Add a claim to a person. */
    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.add(sheetId.trim(), SignedInUser.name(user), personId,
                form.amount(), form.date(), form.note(), correctsOf(form), form.ref(), form.tags()));
    }

    /** Edit a claim. */
    @PutMapping("/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.update(sheetId.trim(), SignedInUser.name(user), id,
                form.amount(), form.date(), form.note(), correctsOf(form), form.ref(), form.tags()));
    }

    /** Open a claim's editor, locking the claim for other users. */
    @PostMapping("/claims/{id}/lock")
    public ClaimView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.lock(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Cancel a claim's editor, releasing the user's lock on it. */
    @PostMapping("/claims/{id}/unlock")
    public ClaimView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.unlock(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Open a draft claim. */
    @PostMapping("/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.OPEN));
    }

    /** Delete a draft claim. */
    @PostMapping("/claims/{id}/delete")
    public ClaimView delete(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.delete(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Restore a deleted claim, on the sheet's today it was deleted, as a draft again. */
    @PostMapping("/claims/{id}/restore")
    public ClaimView restore(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.restore(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Open the selected claims together in list order; each that cannot be opened is left as it is. */
    @PostMapping("/claims/open")
    public BulkOpenView openSelected(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkOpenForm form) {
        BulkChangeService.Result result = bulk.open(sheetId.trim(), SignedInUser.name(user), form.ids());
        return new BulkOpenView(result.changed().size(), result.refused(),
                result.changed().stream().map(ClaimView::of).toList());
    }

    /** Settle the selected claims together in list order; one left pending counts as settled, each
     *  that cannot be settled is left as it is. */
    @PostMapping("/claims/settle")
    public BulkSettleView settleSelected(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkOpenForm form) {
        BulkChangeService.SettleResult result = bulk.settle(sheetId.trim(), SignedInUser.name(user), form.ids());
        return new BulkSettleView(result.changed().size(), result.refused(),
                result.changed().stream().map(ClaimView::of).toList(), result.batch());
    }

    /** Move the selected claims to another person together in list order; each that cannot be moved
     *  is left as it is. */
    @PostMapping("/claims/reassign")
    public BulkReassignView reassignSelected(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkReassignForm form) {
        BulkChangeService.Result result = bulk.reassign(sheetId.trim(), SignedInUser.name(user), form.ids(),
                form.personId());
        return new BulkReassignView(result.changed().size(), result.refused(),
                result.changed().stream().map(ClaimView::of).toList());
    }

    /** Re-date the selected claims together in list order; each that cannot be re-dated is left as it is. */
    @PostMapping("/claims/redate")
    public BulkRedateView redateSelected(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkRedateForm form) {
        BulkChangeService.Result result = bulk.redate(sheetId.trim(), SignedInUser.name(user), form.ids(), form.date());
        return new BulkRedateView(result.changed().size(), result.refused(),
                result.changed().stream().map(ClaimView::of).toList());
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

    /** Review a claim waiting for review, opening it; only a steward may. */
    @PostMapping("/claims/{id}/review")
    public ClaimView review(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.review(sheetId.trim(), SignedInUser.name(user), id));
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

    /** Re-settle a settled claim at what it would be due today as an open, unbound claim; a steward's change. */
    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.resettle(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** The date a claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    /** Defer an open claim until a date after the sheet's today. */
    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid DeferForm form) {
        return ClaimView.of(service.defer(sheetId.trim(), SignedInUser.name(user), id, form.until()));
    }

    /** Raise a claim's proofs count by one; refused for a settled or voided claim. */
    @PostMapping("/claims/{id}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.addProof(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Remind an open or pending claim, once per sheet's today. */
    @PostMapping("/claims/{id}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.remind(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** The new date of a claim. */
    public record RedateForm(@NotNull LocalDate date) {
    }

    /** Re-date an open or pending claim; only a steward may. */
    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RedateForm form) {
        return ClaimView.of(service.redate(sheetId.trim(), SignedInUser.name(user), id, form.date()));
    }

    /** The amount split off a claim. */
    public record SplitForm(@NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** What a split did: the claim split, with the rest of its amount, and the new claim split off it. */
    public record SplitView(ClaimView original, ClaimView split) {
    }

    /** Split an amount off an open claim into a new open claim. */
    @PostMapping("/claims/{id}/split")
    @ResponseStatus(HttpStatus.CREATED)
    public SplitView split(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid SplitForm form) {
        ClaimService.Split split = service.split(sheetId.trim(), SignedInUser.name(user), id, form.amount());
        return new SplitView(ClaimView.of(split.original()), ClaimView.of(split.split()));
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

    /** The reason a claim is voided with, chosen from the sheet's list; none while the list is empty. */
    public record VoidForm(@Size(max = 100) String reason) {
    }

    /** Void a draft or open (or pending) claim, or, as a steward, a settled one within 7 days of its settlement;
     *  while the sheet has void reasons, with one of them. */
    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody(required = false) @Valid VoidForm form) {
        return ClaimView.of(service.voidClaim(sheetId.trim(), SignedInUser.name(user), id,
                form == null ? null : form.reason()));
    }

    /** The text of a comment on a claim. */
    public record CommentForm(@NotBlank @Size(max = 1000) String text) {
    }

    /** Comment a claim; an empty comment is refused. */
    @PostMapping("/claims/{id}/comments")
    public ClaimView comment(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid CommentForm form) {
        return ClaimView.of(service.comment(sheetId.trim(), SignedInUser.name(user), id, form.text()));
    }

    /** A line added to a claim: a label, a whole quantity of at least 1 and a unit amount with two decimals. */
    public record LineForm(@NotBlank @Size(max = 200) String label, @NotNull @Min(1) Integer qty,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unit) {
    }

    /** Add a line to a draft claim; its amount becomes the total of its lines. */
    @PostMapping("/claims/{id}/lines")
    public ClaimView addLine(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid LineForm form) {
        return ClaimView.of(service.addLine(sheetId.trim(), SignedInUser.name(user), id, form.label(), form.qty(),
                form.unit()));
    }
}
