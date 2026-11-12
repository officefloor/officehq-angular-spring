package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.AsOf;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The people's claims on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** A claim as the screen shows it: the amount with two decimals, the date YYYY-MM-DD, no number unless opened,
     *  the due fixed when it was settled (none while its due is live)
     *  who opened it (none while a draft), whether its person's last statement bound it and the figure
     *  it bound (none while unbound, or when that was n/a), and the number a correction corrects (none
     *  otherwise), its status log, oldest first, and the date it is deferred until (none when never deferred), its proofs count,
     *  the user whose editor is open on it (none while unlocked), and whether it was added while its period
     *  was soft-closed, and the last day it was reminded (none when never), and its external reference (none when
     *  not given). */
    public record ClaimView(long id, long personId, String amount, String date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, String corrects, String dueBy, List<StatusLine> statusLog, String deferredUntil,
            int proofs, String splitFrom, String lockedBy, boolean lateEntry,
            String remindedOn, String ref) {

        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate().toString(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects(), claim.getDueBy().toString(),
                    claim.getStatusLog().stream().map(StatusLine::of).toList(),
                    claim.getDeferredUntil() == null ? null : claim.getDeferredUntil().toString(),
                    claim.getProofs(), claim.getSplitFrom(), claim.getLockedBy(),
                    claim.isLateEntry(),
                    claim.getRemindedOn() == null ? null : claim.getRemindedOn().toString(), claim.getRef());
        }
    }

    /** A line of a claim's status log: the status, who changed it, the sheet's today then (YYYY-MM-DD), and
     *  the due it had then, two decimals (none when n/a). */
    public record StatusLine(ClaimStatus status, String by, String on, String due) {

        static StatusLine of(StatusChange change) {
            return new StatusLine(change.getStatus(), change.getBy(), change.getOn().toString(),
                    change.getDue() == null ? null : change.getDue().toPlainString());
        }
    }

    /** A claim's fields; a missing date takes the sheet's today, a missing note is empty. A negative
     *  amount (a correction) names the number of the claim it corrects. */
    public record ClaimRequest(
            @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 220) String corrects,
            @Size(max = 200) String ref) {

        String noteOrEmpty() {
            return note == null ? "" : note.trim();
        }

        /** The external reference, trimmed; none when blank. */
        String refOrNone() {
            return ref == null || ref.isBlank() ? null : ref.trim();
        }

        /** The number a correction names, trimmed; none when blank. */
        String correctsOrNone() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }
    }

    /** The quick-add bar's name and amount. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount) {
    }

    /** The person a quick-add claimed for, whether it added them, and the claim. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    /** The paste box's lines of name,amount,date,note. */
    public record PasteRequest(@NotNull @Size(max = 1000000) String text) {
    }

    /** How many a paste added, and the claims it added, in the order of their lines. */
    public record PasteView(int added, List<ClaimView> claims) {
    }

    private final ClaimService service;
    private final QuickAddService quickAdd;
    private final PasteService paste;
    private final PersonService people;
    private final AsOf asOf;

    public ClaimController(ClaimService service, QuickAddService quickAdd, PasteService paste, PersonService people,
            AsOf asOf) {
        this.asOf = asOf;
        this.service = service;
        this.quickAdd = quickAdd;
        this.paste = paste;
        this.people = people;
    }

    /** The sheet's claims, newest first; as they stood at the end of a date when the screen views the sheet as
     *  of one. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return (asOf == null ? service.list(sheetId.trim()) : this.asOf.claims(sheetId.trim(), asOf)).stream()
                .map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    public ClaimView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, request.amount(), request.date(),
                request.noteOrEmpty(), request.correctsOrNone(), request.refOrNone()));
    }

    /** Add a DRAFT claim dated the sheet's today for the person with the name, adding them first when
     *  the sheet has no one by it. */
    @PostMapping("/quick-claims")
    public QuickAddView quickAdd(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid QuickAddRequest request) {
        QuickAddService.Added added = quickAdd.add(sheetId.trim(), user.trim(), request.name(), request.amount());
        return new QuickAddView(PersonView.of(added.person(), people.today(sheetId.trim()), people.statementsOf(added.person().getId()),
                people.patronsOf(added.person().getId()), people.watching(added.person().getId(), user.trim())),
                added.personAdded(), ClaimView.of(added.claim()));
    }

    /** Add a DRAFT claim for each pasted line, in order; one refused line refuses them all. */
    @PostMapping("/claims/paste")
    public PasteView paste(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PasteRequest request) {
        List<Claim> added = paste.paste(sheetId.trim(), user.trim(), request.text());
        return new PasteView(added.size(), added.stream().map(ClaimView::of).toList());
    }

    /** Change a DRAFT claim's amount, date and note. */
    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), user.trim(), claimId, request.amount(), request.date(),
                request.noteOrEmpty(), request.correctsOrNone(), request.refOrNone()));
    }

    /** Open a DRAFT claim's editor, locking it for every other user. */
    @PostMapping("/claims/{claimId}/lock")
    public ClaimView lock(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.lock(sheetId.trim(), user.trim(), claimId));
    }

    /** Close a claim's editor without saving, releasing the user's lock on it. */
    @DeleteMapping("/claims/{claimId}/lock")
    public ClaimView unlock(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.unlock(sheetId.trim(), user.trim(), claimId));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    /** The claims chosen for a bulk action. */
    public record BulkRequest(@NotNull @Size(max = 10000) List<@NotNull Long> claimIds) {
    }

    /** How many a bulk open opened and refused, and the claims it opened. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    /** Open the chosen claims together, in list order; each that cannot be opened is left as it is. */
    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimService.BulkOpened done = service.bulkOpen(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkOpenView(done.opened(), done.refused(), done.claims().stream().map(ClaimView::of).toList());
    }

    /** How many a bulk settle settled (PENDING included) and refused, and the claims it changed. */
    public record BulkSettleView(int settled, int refused, List<ClaimView> claims) {
    }

    /** Settle the chosen claims together, in list order; each that cannot be settled is left as it is. */
    @PostMapping("/claims/bulk-settle")
    public BulkSettleView bulkSettle(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimService.BulkSettled done = service.bulkSettle(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkSettleView(done.settled(), done.refused(), done.claims().stream().map(ClaimView::of).toList());
    }

    /** Settle an OPEN claim; a clerk settling a due above the clerk limit puts it PENDING instead. */
    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    /** Approve a PENDING claim: a steward other than its opener settles it. */
    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.approve(sheetId.trim(), user.trim(), claimId));
    }

    /** Raise a claim's proofs count by one. */
    @PostMapping("/claims/{claimId}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.addProof(sheetId.trim(), user.trim(), claimId));
    }

    /** Remind an OPEN or PENDING claim, once per sheet's today. */
    @PostMapping("/claims/{claimId}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.remind(sheetId.trim(), user.trim(), claimId));
    }

    /** Re-settle a SETTLED claim at its figure today: a steward freezes it again. */
    @PostMapping("/claims/{claimId}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.resettle(sheetId.trim(), user.trim(), claimId));
    }

    /** Why a PENDING claim is rejected. */
    public record RejectRequest(@Size(max = 1000) String reason) {
    }

    /** Reject a PENDING claim with a reason: a steward sends it back to OPEN. */
    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RejectRequest request) {
        return ClaimView.of(service.reject(sheetId.trim(), user.trim(), claimId,
                request.reason() == null ? "" : request.reason().trim()));
    }

    /** The date a claim is deferred until. */
    public record DeferRequest(LocalDate until) {
    }

    /** Defer an OPEN claim to a date after the sheet's today. */
    @PostMapping("/claims/{claimId}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid DeferRequest request) {
        return ClaimView.of(service.defer(sheetId.trim(), user.trim(), claimId, request.until()));
    }

    /** The date a claim is re-dated to; a missing date is the sheet's today. */
    public record RedateRequest(LocalDate date) {
    }

    /** Re-date an OPEN or PENDING claim: a steward moves it to another date. */
    @PostMapping("/claims/{claimId}/redate")
    public ClaimView redate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RedateRequest request) {
        return ClaimView.of(service.redate(sheetId.trim(), user.trim(), claimId, request.date()));
    }

    /** The amount split off a claim. */
    public record SplitRequest(@NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount) {
    }

    /** The claim a split kept, with the rest of its amount, and the claim it split off. */
    public record SplitView(ClaimView kept, ClaimView split) {
    }

    /** Split an amount off an OPEN claim into a new OPEN claim of the same person. */
    @PostMapping("/claims/{claimId}/split")
    public SplitView split(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SplitRequest request) {
        ClaimService.Split done = service.split(sheetId.trim(), user.trim(), claimId, request.amount());
        return new SplitView(ClaimView.of(done.kept()), ClaimView.of(done.split()));
    }

    /** The person a claim is moved to. */
    public record ReassignRequest(@NotNull Long personId) {
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet: a steward reassigns it. */
    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ReassignRequest request) {
        return ClaimView.of(service.reassign(sheetId.trim(), user.trim(), claimId, request.personId()));
    }

    /** Void a DRAFT, OPEN or PENDING claim, or a SETTLED one by a steward within 7 days of settling. */
    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }

    /** A person after a statement, with their claims as it bound them, newest first. */
    public record StatementView(PersonView person, List<ClaimView> claims) {
    }

    /** A statement as of a date on or before the sheet's today; none takes it now. */
    public record StatementRequest(LocalDate asOf) {
    }

    /** Take a person's statement, now or as of a date. */
    @PostMapping("/people/{personId}/statement")
    public StatementView statement(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody(required = false) @Valid StatementRequest request) {
        LocalDate asOf = request == null ? null : request.asOf();
        ClaimService.Stated stated = asOf == null ? service.statement(sheetId.trim(), user.trim(), personId)
                : service.statementAsOf(sheetId.trim(), user.trim(), personId, asOf);
        return new StatementView(PersonView.of(stated.person(), people.today(sheetId.trim()), stated.statements(),
                people.patronsOf(stated.person().getId()), people.watching(stated.person().getId(), user.trim())),
                stated.claims().stream().map(ClaimView::of).toList());
    }

    /** Cancel a person's last statement (a steward only). */
    @PostMapping("/people/{personId}/statement/cancel")
    public StatementView cancelStatement(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        ClaimService.Stated stated = service.cancelStatement(sheetId.trim(), user.trim(), personId);
        return new StatementView(PersonView.of(stated.person(), people.today(sheetId.trim()), stated.statements(),
                people.patronsOf(stated.person().getId()), people.watching(stated.person().getId(), user.trim())),
                stated.claims().stream().map(ClaimView::of).toList());
    }

    /** A statement as of a date that cannot be taken is refused with the reason, for the screen to show. */
    @ExceptionHandler(StatementRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> statementRefused(StatementRefusedException e) {
        return Map.of("message", e.getMessage());
    }

    /** A cancel of a statement that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(StatementCancelException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> cancelRefused(StatementCancelException e) {
        return Map.of("message", e.getMessage());
    }

    /** A change to a claim in a closed period is refused with the reason, for the screen to show. */
    @ExceptionHandler(PeriodClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> periodClosed(PeriodClosedException e) {
        return Map.of("message", e.getMessage());
    }

    /** A claim dated after its person's retirement is refused with the reason, for the screen to show. */
    @ExceptionHandler(PersonRetiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> retired(PersonRetiredException e) {
        return Map.of("message", e.getMessage());
    }

    /** A paste with a refused line is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimPasteException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> pasteRefused(ClaimPasteException e) {
        return Map.of("message", e.getMessage());
    }

    /** A reference already used on the sheet is refused with the reason, for the form to show. */
    @ExceptionHandler(ClaimRefException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refUsed(ClaimRefException e) {
        return Map.of("message", e.getMessage(), "field", "ref");
    }

    /** An edit of a claim that is not a DRAFT is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimNotDraftException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> notDraft(ClaimNotDraftException e) {
        return Map.of("message", e.getMessage());
    }

    /** An edit of a claim another user is editing is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimLockedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> locked(ClaimLockedException e) {
        return Map.of("message", e.getMessage());
    }

    /** A correction that names no settled claim of its person, or a claim that corrects but is not
     *  negative, is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimCorrectionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> correctionRefused(ClaimCorrectionException e) {
        return Map.of("message", e.getMessage());
    }

    /** A settle by the user who opened the claim is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimSettlerException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> settlerRefused(ClaimSettlerException e) {
        return Map.of("message", e.getMessage());
    }

    /** A reassignment that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimReassignException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> reassignRefused(ClaimReassignException e) {
        return Map.of("message", e.getMessage());
    }

    /** An approval or rejection that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimApprovalException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> approvalRefused(ClaimApprovalException e) {
        return Map.of("message", e.getMessage());
    }

    /** A deferral that is not allowed, or a settle of a deferred claim, is refused with the reason, for the
     *  screen to show. */
    @ExceptionHandler(ClaimDeferException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> deferRefused(ClaimDeferException e) {
        return Map.of("message", e.getMessage());
    }

    /** A proof that is not allowed, or a settle or approval of an unproved claim above the proof threshold,
     *  is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimProofException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> proofRefused(ClaimProofException e) {
        return Map.of("message", e.getMessage());
    }

    /** An open of a claim with an empty note above the note threshold is refused with the reason, for the
     *  screen to show. */
    @ExceptionHandler(ClaimNoteException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> noteRefused(ClaimNoteException e) {
        return Map.of("message", e.getMessage());
    }

    /** A split that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimSplitException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> splitRefused(ClaimSplitException e) {
        return Map.of("message", e.getMessage());
    }

    /** A re-date that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimRedateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> redateRefused(ClaimRedateException e) {
        return Map.of("message", e.getMessage());
    }

    /** A re-settle that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimResettleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> resettleRefused(ClaimResettleException e) {
        return Map.of("message", e.getMessage());
    }

    /** A reminder that is not allowed is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimRemindException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> remindRefused(ClaimRemindException e) {
        return Map.of("message", e.getMessage());
    }
}
