package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerAllowed;
import net.officefloor.hq.app.sheet.SheetService;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** A claim as the screen shows it: the amount always with two decimals; a SETTLED claim's
     *  due as fixed when it was settled (null, so the due is live, when its grade had no rate then,
     *  and for other claims); who opened it (null while DRAFT); whether the person's last statement
     *  bound it, and the due it was bound to (null when unbound or n/a then); the number of the
     *  claim a correction corrects (null for other claims); its status log, oldest first; the date
     *  it was deferred until (null when never deferred); how many proofs it has; the number of the
     *  claim it was split from (null when not made by a split); the user whose open editor locks
     *  it (null while unlocked); whether it was added while its period was soft-closed; the last
     *  day it was reminded (null when never reminded); its external reference (null when none); its
     *  tags, lower-case, sorted and comma separated (null when none); the reason it was voided with
     *  (null when none); its comments, oldest first; who last edited it (null when never edited);
     *  the number of the bulk settle batch that settled it (null when settled one at a time); its
     *  lines, in the order added; and what it was added as against a forward-only rate's mark (its
     *  own id, or for a copy in a clone the claim it copies was added as); and its review day, the
     *  sheet's today when it went to REVIEW (null when it never did); and whether a steward waived
     *  its late charge; and of its proofs, how many are receipts and how many photos; and whether it
     *  is held; and the people it is shared with, in the order added, each with their share; and the
     *  user its review is assigned to (null when never assigned); and its pending day, the sheet's
     *  today when it last went to PENDING (null when it never did). */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, String corrects, long listed, LocalDate dueBy, List<LogLineView> log,
            LocalDate deferredUntil, int proofs, String splitFrom, String lockedBy,
            boolean lateEntry, LocalDate reminded, String ref, String tags, String voidReason,
            List<CommentView> comments, String editedBy, Integer batch, List<LineView> lines, long added,
            LocalDate reviewOn, boolean waived, int receiptProofs, int photoProofs, boolean held,
            List<ShareView> shares, String reviewAssignee, LocalDate pendingOn) {
        static ClaimView of(Claim c, List<ClaimLog> log, List<ClaimComment> comments, List<ClaimLine> lines) {
            return of(c.getId(), c, log, comments, lines);
        }

        /** The claim of the id, which a version rebuilt from the sheet's history does not carry. */
        static ClaimView of(long id, Claim c, List<ClaimLog> log, List<ClaimComment> comments, List<ClaimLine> lines) {
            return new ClaimView(id, c.getPersonId(), c.getAmount().setScale(2).toPlainString(),
                    c.getDate(), c.getNote(), c.getStatus(), c.getNumber(), money(c.getSettledDue()),
                    c.getOpenedBy(), c.isStated(), money(c.getStatedDue()), c.getCorrects(), c.getListed(), c.getDueBy(),
                    log.stream().map(LogLineView::of).toList(), c.getDeferredUntil(), c.getProofs(), c.getSplitFrom(), c.getLockedBy(),
                    c.isLateEntry(), c.getReminded(), c.getRef(), c.getTags(), c.getVoidReason(),
                    comments.stream().map(CommentView::of).toList(), c.getEditedBy(), c.getBatch(),
                    lines.stream().map(LineView::of).toList(), c.getAdded() != null ? c.getAdded() : id,
                    c.getReviewOn(), c.isWaived(), c.getReceiptProofs(), c.getPhotoProofs(), c.isHeld(),
                    c.getShares().stream().map(ShareView::of).toList(), c.getReviewAssignee(), c.getPendingOn());
        }

        private static String money(BigDecimal value) {
            return value == null ? null : value.setScale(2).toPlainString();
        }
    }

    /** A line of a claim's status log: the status, who moved the claim there, the sheet's today
     *  then, and the claim's due at that moment with two decimals (null: n/a). */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        static LogLineView of(ClaimLog line) {
            return new LogLineView(line.getStatus(), line.getBy(), line.getOn(), ClaimView.money(line.getDue()));
        }
    }

    /** A share of a claim: the person holding it and their share with two decimals. */
    public record ShareView(long personId, String share) {
        static ShareView of(ClaimShare s) {
            return new ShareView(s.getPersonId(), s.getShare().setScale(2).toPlainString());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record CommentView(String by, LocalDate on, String text) {
        static CommentView of(ClaimComment c) {
            return new CommentView(c.getBy(), c.getOn(), c.getText());
        }
    }

    /** A line of a claim: its label, quantity and unit amount with two decimals. */
    public record LineView(String label, int qty, String unit) {
        static LineView of(ClaimLine l) {
            return new LineView(l.getLabel(), l.getQty(), l.getUnit().setScale(2).toPlainString());
        }
    }

    /** A line as the claim submits it: a label that must not be blank, a whole quantity of at
     *  least 1 and a unit amount with up to two decimals. */
    public record LineForm(
            @NotBlank @Size(max = 200) String label,
            @NotNull @Pattern(regexp = "\\d{1,9}") String qty,
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String unit) {
    }

    /** A comment as the claim submits it: its text, which must not be blank. */
    public record CommentForm(@Size(max = 1000) String text) {
    }

    /** A settle as the row submits it: the date it is taken as of; missing, it is taken now. */
    public record SettleForm(LocalDate on) {
    }

    /** A statement as the row submits it: the date it is taken as of; missing, it is taken now. */
    public record StatementForm(LocalDate asOf) {
    }

    /** A statement just taken: the person as they now show, and all their claims, newest first. */
    public record StatementView(PersonView person, List<ClaimView> claims) {
    }

    /** A claim's values as the form submits them: an amount with up to two decimals (negative
     *  for a correction), an optional date (the sheet's today when absent), an optional note, and
     *  the number of the claim a correction corrects, an optional external reference (blank:
     *  none), and its tags, comma separated (blank: none). */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 120) @Pattern(regexp = "\\s*\\S+\\s*") String corrects,
            @Size(max = 120) String ref,
            @Size(max = 1000) String tags) {
    }

    /** The reason a steward gives for rejecting a PENDING claim. */
    /** A void as the screen submits it: the reason chosen from the sheet's list, null or blank for none. */
    public record VoidForm(@Size(max = 200) String reason) {
    }

    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    /** The person a claim is reassigned to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** The date a claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    /** A share as the claim submits it: the person it is shared with and a percent with up to two
     *  decimals. */
    public record ShareForm(@NotNull Long personId,
            @NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String share) {
    }

    /** The new date a claim is re-dated to. */
    public record RedateForm(@NotNull LocalDate date) {
    }

    /** The user name a REVIEW claim's review is assigned to. */
    public record AssignReviewForm(@NotNull @Size(max = 100) String user) {
    }

    /** The amount split off a claim: up to two decimals. */
    public record SplitForm(@NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount) {
    }

    /** A split just made: the original as it now is, and the new claim split off it. */
    public record SplitView(ClaimView original, ClaimView split) {
    }

    /** The claims selected for a bulk action, by id. */
    public record BulkForm(@NotNull @Size(max = 10000) List<@NotNull Long> ids) {
    }

    /** A bulk open's result: how many of the selected claims were opened and how many refused. */
    public record BulkOpenView(int opened, int refused) {
    }

    /** The claims selected for a bulk reassign, by id, and the person they are moved to. */
    public record BulkReassignForm(@NotNull @Size(max = 10000) List<@NotNull Long> ids, @NotNull Long personId) {
    }

    /** A bulk reassign's result: how many of the selected claims were moved and how many refused. */
    public record BulkReassignView(int reassigned, int refused) {
    }

    /** The claims selected for a bulk re-date, by id, and the new date they are re-dated to. */
    public record BulkRedateForm(@NotNull @Size(max = 10000) List<@NotNull Long> ids, @NotNull LocalDate date) {
    }

    /** A bulk re-date's result: how many of the selected claims were re-dated and how many refused. */
    public record BulkRedateView(int redated, int refused) {
    }

    /** The claims selected for a bulk void, by id, and the reason chosen from the sheet's list
     *  (null or blank for none). */
    public record BulkVoidForm(@NotNull @Size(max = 10000) List<@NotNull Long> ids, @Size(max = 200) String reason) {
    }

    /** A bulk void's result: how many of the selected claims were voided and how many refused. */
    public record BulkVoidView(int voided, int refused) {
    }

    /** A bulk settle's result: how many of the selected claims were settled (a claim sent to
     *  PENDING counts) and how many refused. */
    public record BulkSettleView(int settled, int refused) {
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** The claim as the screen shows it, with its status log. */
    public ClaimView view(Claim c) {
        return ClaimView.of(c, service.logOf(c.getId()), service.commentsOf(c.getId()), service.linesOf(c.getId()));
    }

    private final ClaimService service;
    private final PersonService people;
    private final SettingsService settings;
    private final SheetService sheets;
    private final HistoryService history;

    public ClaimController(ClaimService service, PersonService people, SettingsService settings, SheetService sheets,
            HistoryService history) {
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
        this.history = history;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Map<Long, List<ClaimLog>> logs = service.logs(sheetId);
        Map<Long, List<ClaimComment>> comments = service.comments(sheetId);
        Map<Long, List<ClaimLine>> lines = service.lines(sheetId);
        if (asOf != null) {
            return history.asOf(sheetId, asOf).claims().entrySet().stream()
                    .map(e -> ClaimView.of(e.getKey(), e.getValue(), logs.getOrDefault(e.getKey(), List.of()).stream()
                            .filter(l -> !l.getOn().isAfter(asOf)).toList(),
                            comments.getOrDefault(e.getKey(), List.of()).stream()
                                    .filter(c -> !c.getOn().isAfter(asOf)).toList(),
                            lines.getOrDefault(e.getKey(), List.of())))
                    .toList();
        }
        return service.list(sheetId).stream().map(c -> ClaimView.of(c, logs.getOrDefault(c.getId(), List.of()),
                comments.getOrDefault(c.getId(), List.of()), lines.getOrDefault(c.getId(), List.of()))).toList();
    }

    /** Every tag ever used on the sheet, in order; as of a date, those its claims carried then. */
    @GetMapping("/tags")
    public List<String> tags(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.asOf(sheetId, asOf).claims().values().stream()
                    .map(Claim::getTags)
                    .filter(Objects::nonNull)
                    .flatMap(t -> Arrays.stream(t.split(",")))
                    .distinct()
                    .sorted()
                    .toList();
        }
        return service.tagsUsed(sheetId);
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return view(service.add(sheetId, user.trim(), personId, new BigDecimal(body.amount()), body.date(), body.note(), trimmed(body.corrects()),
                body.ref(), body.tags()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm body) {
        return view(service.update(sheetId, user.trim(), id, new BigDecimal(body.amount()), body.date(), body.note(), trimmed(body.corrects()),
                body.ref(), body.tags()));
    }

    /** Open the claim's editor, locking the claim for other users until it is saved or cancelled
     *  or the sheet's today changes. */
    @PostMapping("/claims/{id}/edit")
    public ClaimView edit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.lock(sheetId, user.trim(), id));
    }

    /** Close the claim's editor without saving, releasing the user's lock on it. */
    @PostMapping("/claims/{id}/cancel")
    public ClaimView cancel(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.unlock(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId, user.trim(), id, ClaimStatus.OPEN));
    }

    /** Open each selected claim in list order (people by their place in the list, newest claim first),
     *  each on its own, numbered in that order; a claim that cannot be opened is left as it is. */
    @PostMapping("/claims/bulk-open")
    @ViewerAllowed
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm body) {
        int selected = (int) body.ids().stream().distinct().count();
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new BulkOpenView(0, selected);
        }
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int opened = 0;
        for (long id : ordered) {
            try {
                service.changeStatus(sheetId, user.trim(), id, ClaimStatus.OPEN);
                opened++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkOpenView(opened, selected - opened);
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) SettleForm body) {
        return view(service.settle(sheetId, user.trim(), id, body == null ? null : body.on()));
    }

    /** Settle the selected claims in list order (people by their place in the list, newest claim first)
     *  by the rules of settling one, all or nothing: a claim sent to PENDING counts as settled, and
     *  when any cannot be settled (or is missing) none is, and every one counts as refused. A bulk
     *  settle that settles them is the sheet's next batch, and each claim carries its number. */
    @PostMapping("/claims/bulk-settle")
    @ViewerAllowed
    public BulkSettleView bulkSettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm body) {
        int selected = (int) body.ids().stream().distinct().count();
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new BulkSettleView(0, selected);
        }
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        if (ordered.size() != selected) {
            return new BulkSettleView(0, selected);
        }
        try {
            service.settleBatch(sheetId, user.trim(), ordered);
        } catch (ResponseStatusException e) {
            // refused: the whole batch is rolled back
            return new BulkSettleView(0, selected);
        }
        return new BulkSettleView(selected, 0);
    }

    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody DeferForm body) {
        return view(service.defer(sheetId, user.trim(), id, body.until()));
    }

    @PostMapping("/claims/{id}/remind")
    public ClaimView remind(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.remind(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/waive")
    public ClaimView waive(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.waive(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/review-assignee")
    public ClaimView assignReview(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AssignReviewForm body) {
        return view(service.assignReview(sheetId, user.trim(), id, body.user()));
    }

    @PostMapping("/claims/{id}/hold")
    public ClaimView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.toggleHold(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/comments")
    public ClaimView comment(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CommentForm body) {
        return view(service.comment(sheetId, user.trim(), id, body.text()));
    }

    @PostMapping("/claims/{id}/lines")
    public ClaimView addLine(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LineForm body) {
        return view(service.addLine(sheetId, user.trim(), id, body.label(), Integer.parseInt(body.qty()),
                new BigDecimal(body.unit())));
    }

    /** A proof as the screen submits it: its kind, receipt or photo (blank: none chosen, a receipt). */
    public record ProofForm(@Size(max = 100) String kind) {
    }

    @PostMapping("/claims/{id}/shares")
    public ClaimView share(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ShareForm body) {
        return view(service.share(sheetId, user.trim(), id, body.personId(), new BigDecimal(body.share())));
    }

    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RedateForm body) {
        return view(service.redate(sheetId, user.trim(), id, body.date()));
    }

    @PostMapping("/claims/{id}/proofs")
    public ClaimView addProof(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody(required = false) ProofForm body) {
        String kind = body == null || body.kind() == null || body.kind().isBlank() ? null : body.kind().trim();
        return view(service.addProof(sheetId, user.trim(), id, kind));
    }

    /** A steward makes a REVIEW claim OPEN. */
    @PostMapping("/claims/{id}/review")
    public ClaimView review(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.review(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.approve(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.resettle(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RejectForm body) {
        return view(service.reject(sheetId, user.trim(), id, body.reason().trim()));
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReassignForm body) {
        return view(service.reassign(sheetId, user.trim(), id, body.personId()));
    }

    /** Move each selected claim to the person in list order (people by their place in the list, newest
     *  claim first), each on its own by the rules of reassigning one; a claim that cannot be moved
     *  is left as it is. */
    @PostMapping("/claims/bulk-reassign")
    @ViewerAllowed
    public BulkReassignView bulkReassign(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkReassignForm body) {
        int selected = (int) body.ids().stream().distinct().count();
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new BulkReassignView(0, selected);
        }
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int reassigned = 0;
        for (long id : ordered) {
            try {
                service.reassign(sheetId, user.trim(), id, body.personId(), true);
                reassigned++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkReassignView(reassigned, selected - reassigned);
    }

    /** Re-date each selected claim in list order (people by their place in the list, newest claim
     *  first), each on its own by the rules of re-dating one; a claim that cannot be re-dated is
     *  left as it is. */
    @PostMapping("/claims/bulk-redate")
    @ViewerAllowed
    public BulkRedateView bulkRedate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkRedateForm body) {
        int selected = (int) body.ids().stream().distinct().count();
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new BulkRedateView(0, selected);
        }
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int redated = 0;
        for (long id : ordered) {
            try {
                service.redate(sheetId, user.trim(), id, body.date());
                redated++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkRedateView(redated, selected - redated);
    }

    /** Void each selected claim in list order (people by their place in the list, newest claim
     *  first), each on its own exactly as voiding one would, with the reason chosen; a claim that
     *  cannot be voided is left as it is. */
    @PostMapping("/claims/bulk-void")
    @ViewerAllowed
    public BulkVoidView bulkVoid(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkVoidForm body) {
        int selected = (int) body.ids().stream().distinct().count();
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new BulkVoidView(0, selected);
        }
        String reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        List<Long> ordered = service.inListOrder(sheetId, body.ids());
        int voided = 0;
        for (long id : ordered) {
            try {
                service.voidClaim(sheetId, user.trim(), id, reason);
                voided++;
            } catch (ResponseStatusException e) {
                // refused: left as it is
            }
        }
        return new BulkVoidView(voided, selected - voided);
    }

    @PostMapping("/claims/{id}/split")
    public SplitView split(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SplitForm body) {
        ClaimService.Split s = service.split(sheetId, user.trim(), id, new BigDecimal(body.amount()));
        return new SplitView(view(s.original()), view(s.split()));
    }

    @PostMapping("/claims/{id}/settle-part")
    public SplitView settlePart(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SplitForm body) {
        ClaimService.Split s = service.partSettle(sheetId, user.trim(), id, new BigDecimal(body.amount()));
        return new SplitView(view(s.original()), view(s.split()));
    }

    @PostMapping("/claims/{id}/delete")
    public ClaimView delete(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.delete(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/restore")
    public ClaimView restore(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(service.restore(sheetId, user.trim(), id));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody(required = false) VoidForm body) {
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        return view(service.voidClaim(sheetId, user.trim(), id, reason));
    }

    @PostMapping("/people/{personId}/statement")
    public StatementView statement(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) StatementForm body) {
        return statementView(sheetId,
                service.statement(sheetId, user.trim(), personId, body == null ? null : body.asOf()), user.trim());
    }

    @PostMapping("/people/{personId}/statement/cancel")
    public StatementView cancelStatement(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return statementView(sheetId, service.cancelStatement(sheetId, user.trim(), personId), user.trim());
    }

    private StatementView statementView(String sheetId, ClaimService.Statement s, String user) {
        return new StatementView(PersonView.of(s.person(), people.today(sheetId), s.summary(), people.patronIds(s.person().getId()),
                people.isWatching(s.person().getId(), user), people.clashing(s.person())),
                s.claims().stream().map(this::view).toList());
    }
}
