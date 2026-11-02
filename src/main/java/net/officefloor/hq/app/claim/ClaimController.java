package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.role.ViewerAllowed;
import net.officefloor.hq.app.sheet.ArchivedAllowed;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** What the screen shows of a claim; the amount, and the due fixed when it was settled (null
     *  when there is none), as text with two decimals; who opened it (null when never opened);
     *  whether the last statement bound it, with the due it bound (null when none) and whether it
     *  bound it to n/a; and the number
     *  of the claim it corrects (null when it is not a correction); the date its figures round by
     *  (that of the claim a correction corrects, or else its own); and where it is listed (newest
     *  first by this); and its status log, oldest first; and the day it is deferred until (null when
     *  never deferred); and how many proofs it has; and the number of the claim it was split off
     *  (null when none); and who has its editor open (null when no one); and the last day a reminder
     *  was written for it (null when never); and its external reference (null when none); and its tags,
     *  sorted and comma and space separated (empty when none); and the reason it was voided with (null
     *  when none); and its comments, in the order they were written; and the number of the bulk
     *  settle batch that settled it (null when none, or when it is not settled or pending). */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, boolean statedNa, String corrects, LocalDate roundsBy, long listed, LocalDate dueBy,
            List<LogLineView> log, LocalDate deferredUntil, int proofs, String splitFrom,
            String editLockedBy, boolean lateEntry, LocalDate remindedOn, String ref, String tags,
            String voidReason, List<CommentView> comments, Integer settleBatch) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().setScale(2).toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString(),
                    claim.isStatedNa(), claim.getCorrects(), claim.roundsBy(), claim.getListed(), claim.getDueBy(),
                    claim.getLog().stream().map(LogLineView::of).toList(), claim.getDeferredUntil(),
                    claim.getProofs(), claim.getSplitFrom(), claim.getEditLockedBy(), claim.isLateEntry(),
                    claim.getRemindedOn(), claim.getRef(), tagsText(claim.getTags()),
                    claim.getStatus() == ClaimStatus.VOID ? claim.getVoidReason() : null,
                    claim.getComments().stream().map(CommentView::of).toList(),
                    batchOf(claim.getStatus(), claim.getSettleBatch()));
        }

        /** The claim as it stood at the end of a day: its figures as its revision then has them (see
         *  {@link History}), its log as far as that day, and its number and who opened it only once it
         *  had been opened by then; its proofs as given by that day; what no revision keeps (its note, reference, tags, where it is listed) as it is
         *  now (null: the claim is gone now). No one has its editor open then. */
        static ClaimView asOf(Claim then, Claim now, LocalDate day) {
            Claim kept = now != null ? now : then;
            List<ClaimLog> log = kept.getLog().stream().filter(l -> !l.getOn().isAfter(day)).toList();
            boolean opened = kept.getLog().isEmpty() ? then.getStatus() != ClaimStatus.DRAFT
                    : log.stream().anyMatch(l -> l.getStatus() == ClaimStatus.OPEN);
            return new ClaimView(then.getId(), then.getPersonId(), then.getAmount().setScale(2).toPlainString(),
                    then.getDate(), kept.getNote(), then.getStatus(), opened ? kept.getNumber() : null,
                    then.getSettledDue() == null ? null : then.getSettledDue().setScale(2).toPlainString(),
                    opened ? kept.getOpenedBy() : null, then.isStated(),
                    then.getStatedDue() == null ? null : then.getStatedDue().setScale(2).toPlainString(),
                    then.isStatedNa(), kept.getCorrects(), then.roundsBy(), kept.getListed(), then.getDueBy(),
                    log.stream().map(LogLineView::of).toList(), then.getDeferredUntil(), kept.getProofsOn(day),
                    kept.getSplitFrom(), null, kept.isLateEntry(),
                    kept.getRemindedOn() == null || kept.getRemindedOn().isAfter(day) ? null : kept.getRemindedOn(),
                    kept.getRef(), tagsText(kept.getTags()),
                    then.getStatus() == ClaimStatus.VOID ? kept.getVoidReason() : null,
                    kept.getComments().stream().filter(c -> !c.getOn().isAfter(day)).map(CommentView::of).toList(),
                    batchOf(then.getStatus(), kept.getSettleBatch()));
        }

        /** The settle batch a claim shows: only while it is settled or pending. */
        private static Integer batchOf(ClaimStatus status, Integer batch) {
            return status == ClaimStatus.SETTLED || status == ClaimStatus.PENDING ? batch : null;
        }

        /** The tags as the claim shows them: sorted, comma and space separated; empty when none. */
        private static String tagsText(String tags) {
            return String.join(", ", Tags.of(tags));
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record CommentView(String by, LocalDate on, String text) {
        static CommentView of(ClaimComment comment) {
            return new CommentView(comment.getByUser(), comment.getOn(), comment.getText());
        }
    }

    /** A line of a claim's status log: the status, who changed it to that, the sheet's today then, and
     *  the claim's due at that moment as text with two decimals (null when it had none). */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        static LogLineView of(ClaimLog line) {
            return new LogLineView(line.getStatus(), line.getByUser(), line.getOn(),
                    line.getDue() == null ? null : line.getDue().setScale(2).toPlainString());
        }
    }

    /** The claim form: a required amount with up to two decimals (a negative one is a correction), an
     *  optional date (the sheet's today when left out), a note that may be empty, and the number of
     *  the settled claim a correction corrects, and an external reference, and comma separated tags. */
    public record ClaimForm(@NotNull @Pattern(regexp = "\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date, @Size(max = 500) String note, @Size(max = 120) String corrects,
            @Size(max = 120) String ref, @Size(max = 1000) String tags) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        String noteValue() {
            return note == null ? "" : note.trim();
        }

        /** The number a correction names (1, 2, ... or P1-1, X-1, ...); null when left blank. */
        String correctsValue() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }

        /** The external reference, its surrounding spaces removed; null when left blank. */
        String refValue() {
            return ref == null || ref.isBlank() ? null : ref.trim();
        }

        /** The tags as stored (see {@link Tags}); null when none are named. */
        String tagsValue() {
            return Tags.normalise(tags);
        }
    }

    /** The claims a bulk action is taken on, by id. */
    public record BulkForm(@NotNull @Size(max = 1000) Set<@NotNull Long> claimIds) {
    }

    /** How many claims a bulk open opened, and how many it refused. */
    public record BulkOpened(int opened, int refused) {
    }

    /** How many claims a bulk settle settled (or left pending a steward's approval), and how many it
     *  refused. */
    public record BulkSettled(int settled, int refused) {
    }

    /** The claims a bulk reassign moves, by id, and the person it moves them to. */
    public record BulkReassignForm(@NotNull @Size(max = 1000) Set<@NotNull Long> claimIds, @NotNull Long personId) {
    }

    /** How many claims a bulk reassign moved, and how many it refused. */
    public record BulkReassigned(int reassigned, int refused) {
    }

    private final ClaimService service;
    private final ClaimBulkService bulk;
    private final History history;
    private final SheetService sheets;

    public ClaimController(ClaimService service, ClaimBulkService bulk, History history, SheetService sheets) {
        this.sheets = sheets;
        this.service = service;
        this.bulk = bulk;
        this.history = history;
    }

    /** The claims on the sheet, newest first; as of a day when one is given, as they stood at the end
     *  of it (see {@link History}). */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        String sheet = sheetId.trim();
        if (asOf != null) {
            Map<Long, Claim> now = service.list(sheet).stream()
                    .collect(Collectors.toMap(Claim::getId, Function.identity()));
            return history.asOf(sheet, asOf).claims().stream()
                    .map(then -> ClaimView.asOf(then, now.get(then.getId()), asOf))
                    .sorted(Comparator.comparingLong(ClaimView::listed).reversed()).toList();
        }
        return service.list(sheet).stream().map(ClaimView::of).toList();
    }

    /** The number of the sheet's last settle batch; null when there has been none. */
    public record LastSettleBatch(Integer batch) {
    }

    /** The number of the sheet's last settle batch; as of a day when one is given, the last batch
     *  that had settled (or left pending) one of the claims as they stood at the end of it. */
    @GetMapping("/claims/settle-batch")
    public LastSettleBatch lastSettleBatch(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        String sheet = sheetId.trim();
        if (asOf != null) {
            Map<Long, Claim> now = service.list(sheet).stream()
                    .collect(Collectors.toMap(Claim::getId, Function.identity()));
            return new LastSettleBatch(history.asOf(sheet, asOf).claims().stream()
                    .filter(then -> then.getStatus() == ClaimStatus.SETTLED || then.getStatus() == ClaimStatus.PENDING)
                    .map(then -> now.get(then.getId())).filter(Objects::nonNull)
                    .map(Claim::getSettleBatch).filter(Objects::nonNull)
                    .max(Integer::compare).orElse(null));
        }
        return new LastSettleBatch(sheets.lastSettleBatch(sheet));
    }

    /** Every tag ever used on the sheet, in order. */
    @GetMapping("/tags")
    public List<String> tags(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.tags(sheetId.trim());
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, form.amountValue(), form.date(), form.noteValue(),
                form.correctsValue(), form.refValue(), form.tagsValue()));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.edit(sheetId.trim(), user.trim(), claimId, form.amountValue(), form.date(), form.noteValue(),
                form.correctsValue(), form.refValue(), form.tagsValue()));
    }

    /** A draft's amount changed in its row: the claim form's amount, alone. */
    public record AmountForm(@NotNull @Pattern(regexp = "\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    @PutMapping("/claims/{claimId}/amount")
    public ClaimView editAmount(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AmountForm form) {
        return ClaimView.of(service.editAmount(sheetId.trim(), user.trim(), claimId, form.amountValue()));
    }

    @PostMapping("/claims/{claimId}/editor")
    public ClaimView openEditor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.openEditor(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/editor/cancel")
    public ClaimView closeEditor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.closeEditor(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    @ViewerAllowed
    @ArchivedAllowed
    @PostMapping("/claims/open")
    public BulkOpened openAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm form) {
        ClaimBulkService.Outcome outcome = bulk.open(sheetId.trim(), user.trim(), form.claimIds());
        return new BulkOpened(outcome.done(), outcome.refused());
    }

    @PostMapping("/claims/{claimId}/delete")
    public ClaimView delete(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.delete(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/restore")
    public ClaimView restore(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.restore(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    @ViewerAllowed
    @ArchivedAllowed
    @PostMapping("/claims/settle")
    public BulkSettled settleAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm form) {
        ClaimBulkService.Outcome outcome = bulk.settle(sheetId.trim(), user.trim(), form.claimIds());
        return new BulkSettled(outcome.done(), outcome.refused());
    }

    @PostMapping("/claims/{claimId}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.resettle(sheetId.trim(), user.trim(), claimId));
    }

    /** The day an open claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    @PostMapping("/claims/{claimId}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DeferForm form) {
        return ClaimView.of(service.defer(sheetId.trim(), user.trim(), claimId, form.until()));
    }

    /** The new date a steward gives an open or pending claim. */
    public record RedateForm(@NotNull LocalDate date) {
    }

    @PostMapping("/claims/{claimId}/redate")
    public ClaimView redate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RedateForm form) {
        return ClaimView.of(service.redate(sheetId.trim(), user.trim(), claimId, form.date()));
    }

    @PostMapping("/claims/{claimId}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.addProof(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.remind(sheetId.trim(), user.trim(), claimId));
    }

    /** The text of a comment on a claim. */
    public record CommentForm(@Size(max = 1000) String text) {
    }

    @PostMapping("/claims/{claimId}/comments")
    public ClaimView comment(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CommentForm form) {
        return ClaimView.of(service.comment(sheetId.trim(), user.trim(), claimId, form.text()));
    }

    /** The reason a steward gives for rejecting a pending claim. */
    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.approve(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RejectForm form) {
        return ClaimView.of(service.reject(sheetId.trim(), user.trim(), claimId, form.reason().trim()));
    }

    /** The person a steward moves a claim to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReassignForm form) {
        return ClaimView.of(service.reassign(sheetId.trim(), user.trim(), claimId, form.personId()));
    }

    @ViewerAllowed
    @ArchivedAllowed
    @PostMapping("/claims/reassign")
    public BulkReassigned reassignAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkReassignForm form) {
        ClaimBulkService.Outcome outcome = bulk.reassign(sheetId.trim(), user.trim(), form.claimIds(), form.personId());
        return new BulkReassigned(outcome.done(), outcome.refused());
    }

    /** The amount split off an open claim: above 0.00, with up to two decimals. */
    public record SplitForm(@NotNull @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The claim split, with the rest of its amount, and the new claim split off it. */
    public record SplitView(ClaimView original, ClaimView split) {
    }

    @PostMapping("/claims/{claimId}/split")
    public SplitView split(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SplitForm form) {
        ClaimService.Split split = service.split(sheetId.trim(), user.trim(), claimId, form.amountValue());
        return new SplitView(ClaimView.of(split.original()), ClaimView.of(split.split()));
    }

    /** The void form: the reason chosen from the sheet's list (blank or absent when none). */
    public record VoidForm(@Size(max = 200) String reason) {
    }

    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody(required = false) VoidForm form) {
        return ClaimView.of(service.voidClaim(sheetId.trim(), user.trim(), claimId,
                form == null ? null : form.reason()));
    }
}
