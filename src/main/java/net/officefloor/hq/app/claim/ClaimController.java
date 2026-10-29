package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.settings.ViewerPasses;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim as the screen shows it; the amount always carries two decimals. The settled due is the figure fixed
     * when it was settled, or null while its due is live. Opened-by is the user who opened it, or null. Stated is
     * whether its person's last statement binds it, to the stated due (null for n/a). Corrects is the number of the
     * claim a correction corrects, or null. Rounds-on is the date its rounding follows: its own, or for a correction
     * the date of the claim it corrects. Deferred-until is the date it was deferred until, or null; it is deferred
     * while it is OPEN and the sheet's today is before that date. Reminded-on is the last day a reminder was written, or null. Proofs is how many proofs it has. Split-from is the number of the claim it was split off, or null. Log is its status log, oldest first.
     * Locked-by is the user whose open editor holds it, or null. Late-entry is whether it was added while its period
     * was soft-closed. Ref is its external reference, or null. Tags are its tags, sorted (none for a claim with none).
     * Comments are its comments, in the order they were written. Lines are its lines, in the order they were added.
     * Mark is the mark it was added at (see {@link Claim#getAddedMark}), or null for one just added.
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, String ref, ClaimStatus status,
            String number, String settledDue, String openedBy, String editedBy, boolean stated, String statedDue, String corrects,
            LocalDate roundsOn, LocalDate dueBy, LocalDate deferredUntil, LocalDate remindedOn, int proofs, String splitFrom, List<LogLineView> log,
            String lockedBy, boolean lateEntry, List<String> tags, String voidReason,
            List<CommentView> comments, Integer batch, List<LineView> lines, Long mark) {
        public static ClaimView of(Claim c) {
            return new ClaimView(c.getId(), c.getPersonId(), c.getAmount().toPlainString(), c.getDate(), c.getNote(),
                    c.getRef(), c.getStatus(), c.getNumber(), c.getSettledDue() == null ? null : c.getSettledDue().toPlainString(),
                    c.getOpenedBy(), c.getEditedBy(), c.isStated(), c.getStatedDue() == null ? null : c.getStatedDue().toPlainString(),
                    c.getCorrects(), c.getRoundsOn(), c.getDueBy(), c.getDeferredUntil(), c.getRemindedOn(), c.getProofs(), c.getSplitFrom(), c.getLog().stream().map(LogLineView::of).toList(),
                    c.getLockedBy(), c.isLateEntry(), ClaimService.tagList(c.getTags()), c.getVoidReason(),
                    c.getComments().stream().map(CommentView::of).toList(), c.getSettleBatch(),
                    c.getLines().stream().map(LineView::of).toList(),
                    c.getAddedMark() == Long.MAX_VALUE ? null : c.getAddedMark());
        }
    }

    /** A line of a claim's status log: the status, who made the change, the sheet's today then, and the due then. */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        public static LogLineView of(ClaimLogEntry e) {
            return new LogLineView(e.getStatus(), e.getBy(), e.getOn(),
                    e.getDue() == null ? null : e.getDue().toPlainString());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record CommentView(String by, LocalDate on, String text) {
        public static CommentView of(ClaimComment c) {
            return new CommentView(c.getBy(), c.getOn(), c.getText());
        }
    }

    /**
     * A claim's fields as the form submits them; a missing date takes the sheet's today. A negative amount is a
     * correction, and corrects names the number of the settled claim it corrects. Ref is its external reference;
     * blank for none. Tags are comma separated; blank for none.
     */
    public record ClaimForm(@NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount, LocalDate date,
            @Size(max = 500) String note, @Size(max = 140) String corrects,
            @Size(max = 140) String ref, @Size(max = 1000) String tags) {
    }

    /** The claims a bulk action is to change, by id. */
    public record BulkForm(@NotNull @Size(max = 1000) Set<@NotNull Long> ids) {
    }

    /** The claims a bulk open opened, in the order it numbered them, and how many it refused. */
    public record BulkOpenView(List<ClaimView> opened, int refused) {
    }

    private final ClaimService service;
    private final ClaimBulkService bulk;

    public ClaimController(ClaimService service, ClaimBulkService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    /** All the claims on the sheet, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @Valid @RequestBody ClaimForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.add(user, sheetId, personId, form.amount(), form.date(), form.note(),
                form.corrects(), form.ref(), form.tags()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody ClaimForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.update(user, sheetId, id, form.amount(), form.date(), form.note(),
                form.corrects(), form.ref(), form.tags()));
    }

    /** Every tag ever used on a claim of the sheet, sorted. */
    @GetMapping("/claim-tags")
    public List<String> tags(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.tags(sheetId);
    }

    /** Opens the user's editor on the claim, holding it for them until it is saved or cancelled. */
    @PostMapping("/claims/{id}/lock")
    public ClaimView lock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.lock(user, sheetId, id));
    }

    /** Cancels the user's editor on the claim, releasing it. */
    @PostMapping("/claims/{id}/unlock")
    public ClaimView unlock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.unlock(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{id}/delete")
    public ClaimView delete(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.delete(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/restore")
    public ClaimView restore(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.restore(user, sheetId, id));
    }

    @PostMapping("/claims/bulk-open")
    @ViewerPasses
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkForm form, @SessionAttribute(SessionController.USER) String user) {
        ClaimBulkService.Result result = bulk.open(user, sheetId, form.ids());
        return new BulkOpenView(result.changed().stream().map(ClaimView::of).toList(), result.refused());
    }

    /** The claims a bulk settle settled or made PENDING, in the order it took them, and how many it refused. */
    public record BulkSettleView(List<ClaimView> settled, int refused, Integer batch) {
    }

    @PostMapping("/claims/bulk-settle")
    @ViewerPasses
    public BulkSettleView bulkSettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkForm form, @SessionAttribute(SessionController.USER) String user) {
        ClaimBulkService.SettleResult result = bulk.settle(user, sheetId, form.ids());
        return new BulkSettleView(result.settled().stream().map(ClaimView::of).toList(), result.refused(),
                result.batch());
    }

    /** The claims a bulk move is to move, by id, and the person it moves them to. */
    public record BulkReassignForm(@NotNull @Size(max = 1000) Set<@NotNull Long> ids, @NotNull Long personId) {
    }

    /** The claims a bulk move moved, in the order it took them, and how many it refused. */
    public record BulkReassignView(List<ClaimView> reassigned, int refused) {
    }

    @PostMapping("/claims/bulk-reassign")
    @ViewerPasses
    public BulkReassignView bulkReassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkReassignForm form, @SessionAttribute(SessionController.USER) String user) {
        ClaimBulkService.Result result = bulk.reassign(user, sheetId, form.ids(), form.personId());
        return new BulkReassignView(result.changed().stream().map(ClaimView::of).toList(), result.refused());
    }

    /** The claims a bulk re-date is to re-date, by id, and their new date. */
    public record BulkRedateForm(@NotNull @Size(max = 1000) Set<@NotNull Long> ids, @NotNull LocalDate date) {
    }

    /** The claims a bulk re-date re-dated, in the order it took them, and how many it refused. */
    public record BulkRedateView(List<ClaimView> redated, int refused) {
    }

    @PostMapping("/claims/bulk-redate")
    @ViewerPasses
    public BulkRedateView bulkRedate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkRedateForm form, @SessionAttribute(SessionController.USER) String user) {
        ClaimBulkService.Result result = bulk.redate(user, sheetId, form.ids(), form.date());
        return new BulkRedateView(result.changed().stream().map(ClaimView::of).toList(), result.refused());
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.resettle(user, sheetId, id));
    }

    /** A steward's reason for rejecting a pending claim. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.approve(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/review")
    public ClaimView review(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.review(user, sheetId, id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody RejectForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.reject(user, sheetId, id, form.reason()));
    }

    /** The person a claim is to move to, by id. */
    public record ReassignForm(@NotNull Long personId) {
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody ReassignForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.reassign(user, sheetId, id, form.personId()));
    }

    /** The date an open claim is to be deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody DeferForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.defer(user, sheetId, id, form.until()));
    }

    /** The new date of an open or pending claim. */
    public record RedateForm(@NotNull LocalDate date) {
    }

    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody RedateForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.redate(user, sheetId, id, form.date()));
    }

    /** The amount to split off an open claim. */
    public record SplitForm(@NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    /** An open claim after a split, and the new open claim split off it. */
    /** A line of a claim: its label, quantity and unit amount (two decimals). */
    public record LineView(String label, int qty, String unit) {
        public static LineView of(ClaimLine l) {
            return new LineView(l.getLabel(), l.getQty(), l.getUnit().toPlainString());
        }
    }

    public record SplitView(ClaimView original, ClaimView split) {
    }

    @PostMapping("/claims/{id}/split")
    public SplitView split(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody SplitForm form,
            @SessionAttribute(SessionController.USER) String user) {
        ClaimService.Split split = service.split(user, sheetId, id, form.amount());
        return new SplitView(ClaimView.of(split.original()), ClaimView.of(split.split()));
    }

    @PostMapping("/claims/{id}/remind")
    public ClaimView remind(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.remind(user, sheetId, id));
    }

    /** The text of a comment to add to a claim. */
    public record CommentForm(@Size(max = 500) String text) {
    }

    @PostMapping("/claims/{id}/comments")
    public ClaimView comment(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody CommentForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.comment(user, sheetId, id, form.text()));
    }

    /** A line to add to a draft claim: its label, a whole quantity of at least 1 and a unit amount (two decimals). */
    public record LineForm(@Size(max = 140) String label, @NotNull @Min(1) Integer qty,
            @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal unit) {
    }

    @PostMapping("/claims/{id}/lines")
    public ClaimView addLine(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody LineForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.addLine(user, sheetId, id, form.label(), form.qty(), form.unit()));
    }

    @PostMapping("/claims/{id}/proof")
    public ClaimView addProof(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.addProof(user, sheetId, id));
    }

    /** The reason chosen from the sheet's list to void a claim with; blank or missing for none. */
    public record VoidForm(@Size(max = 140) String reason) {
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody(required = false) VoidForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.voidClaim(user, sheetId, id, form == null ? null : form.reason()));
    }
}
