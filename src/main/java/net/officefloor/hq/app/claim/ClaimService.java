package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people make on a sheet. */
@Service
public class ClaimService {

    /** The number sequence of claims in no period. */
    private static final String NO_PERIOD = "X";

    /** The days after its settlement within which a steward may void a SETTLED claim. */
    private static final int VOID_SETTLED_DAYS = 7;

    private final ClaimRepository claims;
    private final ClaimTagRepository tags;
    private final PersonRepository people;
    private final RateChain rates;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingsService settings;
    private final WatchService watches;
    private final HolidayService holidays;
    private final VoidReasonService voidReasons;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimTagRepository tags, PersonRepository people, RateChain rates,
            PeriodRepository periods, SheetService sheets, RollupService rollup, SettingsService settings,
            WatchService watches, HolidayService holidays, VoidReasonService voidReasons, Audit audit) {
        this.claims = claims;
        this.tags = tags;
        this.people = people;
        this.rates = rates;
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.watches = watches;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.audit = audit;
    }

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /**
     * Adds a claim for the person; a missing date takes the sheet's entry date, or its today when that is blank. Its
     * due-by is fixed as the terms-th business day after its date, skipping weekends and the sheet's holidays then, and its period is the one its due-by falls in: one whose
     * due-by falls in a soft-closed period is a late entry. Refused when its due-by falls in a closed period or when
     * it is dated after the person's retirement date, and a correction (a negative amount) unless it corrects a settled claim of
     * the person.
     */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(by, sheetId, personId, amount, date, note, corrects, null);
    }

    /** Adds a claim as {@link #add(String, String, Long, BigDecimal, LocalDate, String, String, String, String)} does, with no tags. */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(by, sheetId, personId, amount, date, note, corrects, ref, null);
    }

    /**
     * Adds a claim as {@link #add(String, String, Long, BigDecimal, LocalDate, String, String)} does, carrying the
     * external reference (stripped; blank for none). Refused too when the reference is already used on the sheet,
     * comparing without regard to case. Its tags (comma separated) are trimmed, lower-cased and kept once each.
     */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagged) {
        LocalDate on = dateOrEntryDate(sheetId, date);
        Person person = person(sheetId, personId);
        int terms = termsFor(sheetId, person, on);
        LocalDate dueBy = holidays.dueBy(sheetId, on, terms);
        refuseInClosedPeriod(sheetId, dueBy);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        refuseAfterRetirement(person, on);
        String corrected = correctedNumber(sheetId, personId, amount, corrects);
        String reference = refOf(sheetId, ref, null);
        Claim claim = new Claim(sheetId, personId, scaled(amount), on, noteOf(note));
        claim.setRef(reference);
        claim.setTags(tagsOf(tagged));
        claim.setCorrects(corrected);
        claim.setRoundsOn(roundsOn(sheetId, corrected, on));
        claim.setDueBy(dueBy);
        claim.setTerms(terms);
        claim.setLateEntry(periods.existsBySheetIdAndSoftClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(sheetId,
                dueBy, dueBy));
        logStatus(by, sheetId, person, claim);
        Claim added = claims.saveAndFlush(claim);
        keepTags(sheetId, added.getTags());
        audit.recordOnCommit(record("CLAIM_ADDED", by, person, added)
                + (added.getRef() != null ? " ref=" + added.getRef() : "")
                + (added.getTags() != null ? " tags=" + added.getTags() : ""));
        if (added.isLateEntry()) {
            audit.recordOnCommit(record("LATE_ENTRY", by, person, added));
        }
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Edits a claim; its due-by, and so its period, stay as they were fixed. Refused unless it is a draft, and when
     * its due-by falls in a closed period. An open claim is changed by voiding it and adding a new one.
     */
    @Transactional
    public Claim update(String by, String sheetId, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagged) {
        LocalDate on = dateOrToday(sheetId, date);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        refuseLockedByOther(by, claim);
        refuseInClosedPeriod(sheetId, claim.getDueBy());
        refuseAfterRetirement(person(sheetId, claim.getPersonId()), on);
        String corrected = correctedNumber(sheetId, claim.getPersonId(), amount, corrects);
        String reference = refOf(sheetId, ref, claim.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setRoundsOn(roundsOn(sheetId, corrected, on));
        claim.setNote(noteOf(note));
        claim.setRef(reference);
        claim.setTags(tagsOf(tagged));
        claim.setEditedBy(by);
        // the save closes the editor, which releases the claim
        claim.setLockedBy(null);
        Claim saved = claims.saveAndFlush(claim);
        keepTags(sheetId, saved.getTags());
        audit.recordOnCommit(record("CLAIM_EDITED", by, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Opens the user's editor on a claim, which holds it for them: no one else may edit it until the editor is saved
     * or cancelled, or the sheet's today changes. Refused unless it is a draft, when its due-by falls in a closed period,
     * and when another user's editor holds it. An editor the user had open on another claim of the sheet is closed.
     */
    @Transactional
    public Claim lock(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        refuseLockedByOther(by, claim);
        for (Claim held : claims.findBySheetIdAndLockedByNotNull(sheetId)) {
            if (by.equals(held.getLockedBy()) && !held.getId().equals(claim.getId())) {
                held.setLockedBy(null);
            }
        }
        claim.setLockedBy(by);
        return claims.saveAndFlush(claim);
    }

    /** Closes the user's editor on a claim (cancelled), releasing it; a claim it does not hold is left as it is. */
    @Transactional
    public Claim unlock(String by, String sheetId, Long id) {
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (by.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** Once the sheet's today changes, every editor open on its claims is closed, releasing what they held. */
    @EventListener
    @Transactional
    public void unlockAll(TodayChanged change) {
        for (Claim held : claims.findBySheetIdAndLockedByNotNull(change.sheet().getId())) {
            held.setLockedBy(null);
        }
        claims.flush();
    }

    private static void refuseLockedByOther(String by, Claim claim) {
        if (claim.getLockedBy() != null && !claim.getLockedBy().equals(by)) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "That claim is being edited by " + claim.getLockedBy() + ".");
        }
    }

    /**
     * Moves the claim to the status; a change its status does not allow is refused, as is settling by the user
     * who opened it or last edited it, and any change to a claim whose due-by falls in a closed period. A clerk settling a claim whose due is above
     * the clerk limit makes it PENDING instead, for a steward to approve or reject.
     */
    @Transactional
    public Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next) {
        return changeStatus(by, sheetId, id, next, null);
    }

    /**
     * Voids the claim with a reason: while the sheet's list of void reasons is not empty, one chosen from it is
     * needed, and the void record ends with it.
     */
    @Transactional
    public Claim voidClaim(String by, String sheetId, Long id, String reason) {
        return changeStatus(by, sheetId, id, ClaimStatus.VOID, reason);
    }

    private Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next, String reason) {
        Claim claim = lockedClaim(sheetId, id);
        switch (next) {
            case OPEN -> settings.refuseUnlessMay(sheetId, by, "open");
            case SETTLED -> settings.refuseUnlessMay(sheetId, by, "settle");
            case VOID -> settings.refuseUnlessMay(sheetId, by, "void");
            default -> {
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String tail = "";
        // settling is the step from OPEN; a pending claim is settled by approving it
        if (!claim.getStatus().canMoveTo(next) || next == ClaimStatus.PENDING
                || (next == ClaimStatus.SETTLED && claim.getStatus() != ClaimStatus.OPEN)
                || (next == ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.DRAFT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.load(sheetId).getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        if (next == ClaimStatus.SETTLED && by.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user who opened a claim cannot settle it.");
        }
        if (next == ClaimStatus.SETTLED && by.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user who last edited a claim cannot settle it.");
        }
        if (next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            refuseVoidOfSettled(by, sheetId, claim);
            // the frozen figure leaves every total; the claim keeps its number and shows the figure it had
            tail = " reversed=" + text(claim.getSettledDue());
        }
        if (next == ClaimStatus.VOID) {
            String voidReason = voidReasons.refuseUnlessListed(sheetId, reason);
            claim.setVoidReason(voidReason);
            if (voidReason != null) {
                tail += " reason=" + voidReason;
            }
        }
        if (next == ClaimStatus.OPEN) {
            refuseWithoutNote(sheetId, claim);
            number(sheetId, claim);
            claim.setOpenedBy(by);
        }
        Person person = person(sheetId, claim.getPersonId());
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, PENDING, DELETED -> throw new IllegalStateException();
        };
        if (next == ClaimStatus.SETTLED) {
            // the due is fixed at the figure a statement bound it to, else at the rate the person's grade has in
            // force on the claim's date, with the late charge it has gained by today, and at least the floor in force
            // on its date; with none it stays live
            BigDecimal figure = claim.isStated() && claim.getStatedDue() != null ? claim.getStatedDue()
                    : rateFigure(sheetId, person, claim);
            BigDecimal fixed = liveDue(figure, claim, person, sheets.load(sheetId), true);
            refuseWithoutProof(sheetId, claim, fixed);
            if (settings.aboveClerkLimit(sheetId, by, fixed)) {
                next = ClaimStatus.PENDING;
                event = "CLAIM_PENDING";
            } else {
                claim.setSettledDue(fixed);
                tail = " due=" + (fixed == null ? "n/a" : fixed.toPlainString());
            }
        }
        claim.setStatus(next);
        logStatus(by, sheetId, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record(event, by, person, saved) + tail);
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(by, sheetId, person, saved.getNumber());
        }
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Deletes a DRAFT claim: it is DELETED, counts nowhere and can no longer be edited, opened or voided. Refused
     * for a claim in any other status, one another user's editor holds, and in a closed period.
     */
    @Transactional
    public Claim delete(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a DRAFT claim can be deleted.");
        }
        refuseLockedByOther(by, claim);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, claim.getPersonId());
        claim.setStatus(ClaimStatus.DELETED);
        claim.setLockedBy(null);
        logStatus(by, sheetId, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DELETED", by, person, saved));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Restores a DELETED claim to a DRAFT again. Refused for a claim that is not DELETED, unless the sheet's today is
     * the one it was deleted on, and in a closed period.
     */
    @Transactional
    public Claim restore(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a DELETED claim can be restored.");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        LocalDate deletedOn = claim.deletedOn();
        if (deletedOn == null ? today != null : !deletedOn.equals(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be restored on the day it was deleted.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, claim.getPersonId());
        claim.setStatus(ClaimStatus.DRAFT);
        logStatus(by, sheetId, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_RESTORED", by, person, saved));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Defers an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the claim counts
     * in no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for a claim that is
     * not OPEN, a date not after today, and in a closed period.
     */
    @Transactional
    public Claim defer(String by, String sheetId, Long id, LocalDate until) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OPEN claim can be deferred.");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        if (until == null || (today != null && !until.isAfter(today))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim can only be deferred to a date after the sheet's today.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until, today);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DEFERRED", by, person(sheetId, saved.getPersonId()), saved) + " until="
                + until);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Writes a reminder for an OPEN or PENDING claim, which shows the sheet's today as its reminded date. Refused for
     * a claim in any other status and one already reminded on the sheet's today; a reminder changes none of the
     * claim's figures, so one whose due-by falls in a closed period may still be reminded.
     */
    @Transactional
    public Claim remind(String by, String sheetId, Long id) {
        sheets.lock(sheetId);
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded.");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        if (today != null && today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded today.");
        }
        claim.setRemindedOn(today);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("REMIND", by, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    /** Adds a comment to the claim, by the user on the sheet's today. Refused for a blank comment. */
    @Transactional
    public Claim comment(String by, String sheetId, Long id, String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A comment cannot be empty.");
        }
        sheets.lock(sheetId);
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.comment(by, sheets.load(sheetId).getToday(), stripped);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("COMMENT_ADDED", by, person(sheetId, saved.getPersonId()), saved) + " text="
                + stripped);
        return saved;
    }

    /**
     * Raises the claim's proofs count by one. Refused for a SETTLED, VOID or DELETED claim, and in a closed period.
     */
    @Transactional
    public Claim addProof(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot take a proof.");
        }
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", by, person(sheetId, saved.getPersonId()), saved)
                + " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * Refuses settling or approving the claim at the due (null for n/a) when the due is above the proof threshold in
     * force on the claim's date (its person's grade's then when one is set, else the sheet's) and the claim has no
     * proof; an n/a due is within any threshold.
     */
    private void refuseWithoutProof(String sheetId, Claim claim, BigDecimal due) {
        String grade = person(sheetId, claim.getPersonId()).gradeOn(claim.getDate());
        BigDecimal threshold = sheets.load(sheetId).proofThresholdFor(grade, claim.getDate());
        if (threshold != null && due != null && due.compareTo(threshold) > 0 && claim.getProofs() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with a due above " + threshold.toPlainString() + " needs a proof first.");
        }
    }

    /**
     * Refuses opening the claim when its note is empty and its due is above the note threshold in force on its date;
     * an n/a due is within any threshold.
     */
    private void refuseWithoutNote(String sheetId, Claim claim) {
        BigDecimal threshold = sheets.load(sheetId).noteThresholdOn(claim.getDate());
        if (threshold == null || (claim.getNote() != null && !claim.getNote().isBlank())) {
            return;
        }
        BigDecimal due = due(sheetId, person(sheetId, claim.getPersonId()), claim);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with a due above " + threshold.toPlainString() + " needs a note first.");
        }
    }

    /**
     * A steward approves a pending claim: it is settled, its due fixed at the live figure it shows now. Refused
     * unless it is pending, unless the user is a steward, for the user who opened it or last edited it, and in a
     * closed period.
     */
    @Transactional
    public Claim approve(String by, String sheetId, Long id) {
        Claim claim = pendingClaim(by, sheetId, id);
        if (by.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user who opened a claim cannot approve it.");
        }
        if (by.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who last edited a claim cannot approve it.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal fixed = due(sheetId, person, claim);
        refuseWithoutProof(sheetId, claim, fixed);
        claim.setSettledDue(fixed);
        claim.setStatus(ClaimStatus.SETTLED);
        logStatus(by, sheetId, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_APPROVED", by, person, saved) + " due="
                + (fixed == null ? "n/a" : fixed.toPlainString()));
        watches.notifyWatchers(by, sheetId, person, saved.getNumber());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * A steward re-settles a settled claim: its due is frozen again at the figure it would have today as an open
     * claim bound by no statement (today's rates, grades and floor, with the late charge it has gained by today; a
     * correction gains none). Refused unless the user is a steward, unless it is settled, and in a closed period.
     */
    @Transactional
    public Claim resettle(String by, String sheetId, Long id) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-settle a claim.");
        }
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a SETTLED claim can be re-settled.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        BigDecimal fixed = liveDue(rateFigure(sheetId, person, claim), claim, person, sheets.load(sheetId), true);
        claim.setSettledDue(fixed);
        logStatus(by, sheetId, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_RESETTLED", by, person, saved) + " old=" + text(old) + " new="
                + text(fixed));
        watches.notifyWatchers(by, sheetId, person, saved.getNumber());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Numbers the claims a bulk settle settled (or made PENDING) as the sheet's next batch, and returns its number.
     * Refused with nothing to number: a bulk settle that settles none is no batch.
     */
    @Transactional
    public int numberSettleBatch(String sheetId, List<Long> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("A batch needs at least one claim.");
        }
        int batch = sheets.nextSettleBatch(sheetId);
        for (Claim claim : claims.findAllById(ids)) {
            claim.setSettleBatch(batch);
        }
        return batch;
    }

    /**
     * A steward rejects a pending claim with a reason: it is open again, bound again by its person's last statement
     * when that included it. Refused unless it is pending, unless the user is a steward, without a reason, and in a
     * closed period.
     */
    @Transactional
    public Claim reject(String by, String sheetId, Long id, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A rejection needs a reason.");
        }
        Claim claim = pendingClaim(by, sheetId, id);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        // open again, it is no longer settled by the batch that made it pending
        claim.setSettleBatch(null);
        logStatus(by, sheetId, person(sheetId, claim.getPersonId()), claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REJECTED", by, person(sheetId, saved.getPersonId()), saved)
                + " reason=" + why);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * A user whose role may reassign (a steward by the standing rule) moves a DRAFT, OPEN or PENDING claim to another person of the sheet. It keeps its date,
     * takes the next number in the sequence it would be numbered in now (a draft, having none, gets none) and never gives its old one again,
     * loses any figure a statement bound it to (it is live again until its new person's next statement), and is
     * listed as its new person's newest claim. Refused unless the user's role may reassign, for a settled or voided claim,
     * to the person it is already with, in a closed period, and when it is dated after the new person's retirement.
     */
    @Transactional
    public Claim reassign(String by, String sheetId, Long id, Long toPersonId) {
        return reassign(by, sheetId, id, toPersonId, false);
    }

    /**
     * Moves a claim as {@link #reassign(String, String, Long, Long)} does; a bulk move (sameAllowed) also takes a
     * claim already with the person, re-listing it as that person's newest.
     */
    @Transactional
    public Claim reassign(String by, String sheetId, Long id, Long toPersonId, boolean sameAllowed) {
        settings.refuseUnlessMay(sheetId, by, "reassign");
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot move.");
        }
        if (!sameAllowed && claim.getPersonId().equals(toPersonId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already that person's.");
        }
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        refuseAfterRetirement(to, claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        if (claim.getNumber() != null) {
            renumber(sheetId, claim);
        }
        Claim saved = claims.saveAndFlush(claim);
        claims.relist(saved.getId());
        audit.recordOnCommit(record("CLAIM_REASSIGNED", by, to, saved) + " from=" + from.getName() + " to="
                + to.getName());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * A steward re-dates an OPEN or PENDING claim. Its due-by, and so its period and number, stay as they were fixed
     * and a figure a statement bound it to stays bound; its rounding (unless a correction) follows the new date.
     * Refused unless the user is a steward, for a claim that is not OPEN or PENDING, for a claim whose due-by falls
     * in a closed period, and for a new date after the person's retirement.
     */
    @Transactional
    public Claim redate(String by, String sheetId, Long id, LocalDate date) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-date a claim.");
        }
        Claim claim = lockedClaim(sheetId, id);
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot be re-dated.");
        }
        Person person = person(sheetId, claim.getPersonId());
        refuseAfterRetirement(person, date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        if (!claim.isCorrection()) {
            claim.setRoundsOn(date);
        }
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REDATED", by, person, saved) + " old=" + old + " new=" + date);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /** An OPEN claim after a split, and the new OPEN claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /**
     * Splits an amount (more than 0.00 and less than the claim's) off an OPEN claim: the claim keeps the rest and
     * loses any figure a statement bound it to; a new OPEN claim with the amount, the same date and note, the next
     * number, opened by the user, with a due-by from the terms in force on the date, and split from the original's
     * number, and a late entry when the original is, is listed as its person's newest. Refused unless the user's
     * role may open a claim, for a claim that is not OPEN or is deferred, for an amount out of range, and when the
     * original's due-by falls in a closed period (the new claim's own due-by is not judged).
     */
    @Transactional
    public Split split(String by, String sheetId, Long id, BigDecimal amount) {
        settings.refuseUnlessMay(sheetId, by, "open");
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OPEN claim can be split.");
        }
        if (claim.isDeferredOn(sheets.load(sheetId).getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The amount split off must be more than 0.00 and less than the claim's.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, claim.getPersonId());
        claim.setAmount(claim.getAmount().subtract(scaled(amount)));
        claim.unbind();
        Claim original = claims.saveAndFlush(claim);
        Claim part = new Claim(sheetId, claim.getPersonId(), scaled(amount), claim.getDate(), claim.getNote());
        part.setRoundsOn(claim.getRoundsOn());
        int terms = termsFor(sheetId, person, claim.getDate());
        part.setDueBy(holidays.dueBy(sheetId, claim.getDate(), terms));
        part.setTerms(terms);
        part.setSplitFrom(claim.getNumber());
        part.setTags(claim.getTags());
        // a split is judged by the original alone: the part is a late entry when the original is
        part.setLateEntry(claim.isLateEntry());
        part.setStatus(ClaimStatus.OPEN);
        number(sheetId, part);
        part.setOpenedBy(by);
        logStatus(by, sheetId, person, part);
        Claim added = claims.saveAndFlush(part);
        audit.recordOnCommit(record("CLAIM_SPLIT", by, person, original) + " into=" + added.getNumber());
        audit.recordOnCommit(record("CLAIM_OPENED", by, person, added));
        rollup.recordChanges(by, sheetId, before);
        return new Split(original, added);
    }

    /**
     * Refuses voiding a SETTLED claim unless the user is a steward and the sheet's today is within
     * {@link #VOID_SETTLED_DAYS} days of the date its status log records it settled.
     */
    private void refuseVoidOfSettled(String by, String sheetId, Claim claim) {
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can void a settled claim.");
        }
        LocalDate settledOn = claim.settledOn();
        LocalDate today = sheets.load(sheetId).getToday();
        if (settledOn == null || today == null || today.isBefore(settledOn)
                || ChronoUnit.DAYS.between(settledOn, today) > VOID_SETTLED_DAYS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A settled claim can only be voided within " + VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** Logs the status the claim now has: by the user, on the sheet's today, at the due it shows now. */
    private void logStatus(String by, String sheetId, Person person, Claim claim) {
        claim.logStatus(by, sheets.load(sheetId).getToday(), due(sheetId, person, claim));
    }

    /** The pending claim, locked, for a steward to decide; refused otherwise. */
    private Claim pendingClaim(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a PENDING claim can be approved or rejected.");
        }
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can approve or reject a claim.");
        }
        return claim;
    }

    /** The claim, locked; refused when its due-by falls in a closed period. */
    private Claim lockedClaim(String sheetId, Long id) {
        // the sheet is locked before the claim, in the order a period closing takes them
        sheets.lock(sheetId);
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseInClosedPeriod(sheetId, claim.getDueBy());
        return claim;
    }

    /**
     * Gives the claim being opened its number: while the sheet's period numbering from is set and its today is on or
     * after it, the next in the sequence of the period the claim's due-by falls in (X for none), else the sheet's next.
     * The sheet is locked, so two claims opened at once never get the same one.
     */
    private void number(String sheetId, Claim claim) {
        if (!numbersByPeriod(sheetId)) {
            claim.setNumber(String.valueOf(sheets.nextClaimNumber(sheetId)));
            return;
        }
        String sequence = sequenceOn(sheetId, claim.getDueBy());
        Long last = claims.lastInSequence(sheetId, sequence);
        claim.numberIn(sequence, last == null ? 1 : last + 1);
    }

    /**
     * Takes in a claim moved to the sheet from another: one that had a number there gets the sheet's next, as one
     * being opened would, and its tags are kept as used on the sheet.
     */
    @Transactional
    public void takeIn(String sheetId, Claim claim) {
        if (claim.getNumber() != null) {
            claim.clearNumber();
            number(sheetId, claim);
        }
        keepTags(sheetId, claim.getTags());
    }

    /** Gives a claim copied onto the sheet from another, where it had a number, the sheet's next. */
    @Transactional
    public void numberCopy(String sheetId, Claim claim) {
        claim.clearNumber();
        number(sheetId, claim);
    }

    /**
     * Gives a numbered claim the next number in the sequence it would be numbered in now; its old one is retired, so it
     * is never given again (a sheet number never is: the sheet's count only rises).
     */
    private void renumber(String sheetId, Claim claim) {
        if (claim.getNumberSequence() != null) {
            claims.retireNumber(sheetId, claim.getNumberSequence(), claim.getNumberInSequence());
        }
        claim.clearNumber();
        number(sheetId, claim);
    }

    /** Whether the sheet numbers claims by period: its period numbering from is set and its today on or after it. */
    private boolean numbersByPeriod(String sheetId) {
        Sheet sheet = sheets.load(sheetId);
        LocalDate from = sheet.getPeriodNumberingFrom();
        return from != null && !sheet.getToday().isBefore(from);
    }

    /** The number sequence of a claim due by the date: the name of the period it is in, or X for none. */
    private String sequenceOn(String sheetId, LocalDate date) {
        return periods.findFirstBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(sheetId, date, date)
                .map(Period::getName).orElse(NO_PERIOD);
    }

    /**
     * The claim's rate figure: at the rate its person's grade in force on its date has in force on that date, rounded
     * as the sheet's policy date decides for it, with no floor; null (n/a) with none.
     */
    public BigDecimal rateFigure(String sheetId, Person person, Claim claim) {
        Sheet sheet = sheets.load(sheetId);
        RoundingMode rounding = sheet.roundingOn(claim.getRoundsOn());
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                .map(pct -> dueOn(claim.getAmount(), pct, rounding)).orElse(null);
    }

    /** What a candidate rate for a grade would change: how many claims would show a different due, and by how much. */
    public record RatePreview(int count, BigDecimal diff) {
    }

    /**
     * Previews the grade's rate at the percent on every date, saving nothing: the OPEN and PENDING claims whose
     * person has the grade in force on the claim's date and whose due would differ, and the sum of new due minus
     * current due (an n/a due counts as 0.00). A claim bound by a statement does not change.
     */
    @Transactional(readOnly = true)
    public RatePreview previewRate(String sheetId, String grade, BigDecimal pct) {
        Sheet sheet = sheets.load(sheetId);
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (!claim.getStatus().isOpen() || claim.getSettledDue() != null || claim.isBound()) {
                continue;
            }
            Person person = person(sheetId, claim.getPersonId());
            if (!grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal current = due(sheetId, person, claim);
            BigDecimal figure = dueOn(claim.getAmount(), pct, sheet.roundingOn(claim.getRoundsOn()));
            BigDecimal next = liveDue(figure, claim, person, sheet, true);
            BigDecimal before = current == null ? BigDecimal.ZERO : current;
            if (current == null || before.compareTo(next) != 0) {
                count++;
                diff = diff.add(next.subtract(before));
            }
        }
        return new RatePreview(count, diff.setScale(2));
    }

    /**
     * The figure of the claim: its settled due, else the one a statement bound it to (not while it is pending), else
     * its rate figure. A statement binds this figure, under the late charge and the floor.
     */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isBound() ? claim.getStatedDue() : rateFigure(sheetId, person, claim);
    }

    /**
     * The due the claim shows and totals: its settled due as frozen, else its live due on its figure (the late charge
     * only while it is open or pending).
     */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figure(sheetId, person, claim);
        return claim.getSettledDue() != null ? figure
                : liveDue(figure, claim, person, sheets.load(sheetId), claim.getStatus().isOpen());
    }

    /**
     * The live due of a claim on its (unfloored) figure: the greater of the floor in force on its date and the figure
     * plus the late charge it has gained (when charged); a correction has neither floor nor charge. Null (n/a) for an
     * n/a figure.
     */
    public static BigDecimal liveDue(BigDecimal figure, Claim claim, Person person, Sheet sheet, boolean charged) {
        if (figure == null || claim.isCorrection()) {
            return figure;
        }
        BigDecimal due = charged ? withLateCharge(figure, claim, person, sheet) : figure;
        return sheet == null ? due : floored(due, sheet.floorOn(claim.getDate()));
    }

    /**
     * The figure (null for n/a) of the claim, with a late charge added for every full 30 days it is overdue (past its
     * late-from: its due-by plus its grace) by the sheet's today: for each such boundary (its late-from plus 30, 60,
     * 90 ... days), the sheet's late
     * percent in force on that boundary date of the figure plus the charges of the boundaries before it (it
     * compounds), rounded like its due; a boundary whose percent is 0.00 adds nothing. A deferred claim is not overdue. The charge is never bound by a statement: it keeps following the
     * sheet's today and its policy date.
     */
    public static BigDecimal withLateCharge(BigDecimal figure, Claim claim, Person person, Sheet sheet) {
        if (figure == null || sheet == null || sheet.getToday() == null || claim.isDeferredOn(sheet.getToday())) {
            return figure;
        }
        LocalDate lateFrom = lateFrom(claim, person, sheet);
        long overdue = ChronoUnit.DAYS.between(lateFrom, sheet.getToday());
        long periods = overdue > 0 ? overdue / 30 : 0;
        RoundingMode rounding = sheet.roundingOn(claim.getRoundsOn());
        BigDecimal due = figure;
        for (long k = 1; k <= periods; k++) {
            BigDecimal latePct = sheet.latePctOn(lateFrom.plusDays(30 * k));
            if (latePct.signum() != 0) {
                due = due.add(dueOn(due, latePct, rounding));
            }
        }
        return due;
    }

    /**
     * The date after which the claim is overdue: its due-by plus the grace days the sheet has in force on the claim's
     * date for its person's grade in force on that date (none: its due-by). Its late charge's 30-day boundaries count
     * from it; the due-by the claim shows is unchanged.
     */
    public static LocalDate lateFrom(Claim claim, Person person, Sheet sheet) {
        if (claim.getDueBy() == null || person == null || sheet == null) {
            return claim.getDueBy();
        }
        return claim.getDueBy().plusDays(sheet.graceOn(person.gradeOn(claim.getDate()), claim.getDate()));
    }

    /**
     * Refuses a change to a claim due by the date when a closed period contains it. The sheet stays locked until
     * the transaction ends, so a period closing at the same moment is checked in turn.
     */
    private void refuseInClosedPeriod(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        if (periods.existsBySheetIdAndClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim's due-by falls in a closed period.");
        }
    }

    /**
     * The number a claim with the amount corrects: for a correction (a negative amount), the number of a settled
     * claim of the person, else refused; null for any other claim.
     */
    private String correctedNumber(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        corrects = corrects == null ? null : corrects.strip();
        if (corrects == null || !claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects,
                ClaimStatus.SETTLED)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must correct a settled claim of the person, named by its number.");
        }
        return corrects;
    }

    /**
     * Refuses a claim for the person dated after their retirement date, or after the from-date and before the to-date
     * of a past retirement; one on or before the retirement date, or on either end of a past one, is allowed.
     */
    private static void refuseAfterRetirement(Person person, LocalDate date) {
        if (person.refusesClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That claim is dated after the person's retirement.");
        }
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /**
     * The terms a claim of the person dated on the date has: the person's own, else those of their grade on the date,
     * else the sheet's on the date.
     */
    private int termsFor(String sheetId, Person person, LocalDate on) {
        return person.getTerms() != null ? person.getTerms()
                : sheets.load(sheetId).termsFor(person.gradeOn(on), on);
    }

    /** The audit line about a claim; one that has a number ends with it. */
    private static String record(String event, String by, Person person, Claim c) {
        String number = c.getNumber();
        return event + " sheet=" + c.getSheetId() + " by=" + by + " person=" + person.getName()
                + " amount=" + c.getAmount().toPlainString() + " date=" + c.getDate()
                + (number != null ? " number=" + number : "");
    }

    /**
     * The due on an amount at a rate: amount times rate divided by 100, rounded to two decimals the given way (a
     * negative amount rounds like its positive).
     */
    public static BigDecimal dueOn(BigDecimal amount, BigDecimal pct, RoundingMode rounding) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, rounding);
    }

    /** The date a claim dated on the date rounds by: that date, or for a correction the corrected claim's date. */
    private LocalDate roundsOn(String sheetId, String corrects, LocalDate on) {
        return corrects == null ? on
                : claims.findBySheetIdAndNumber(sheetId, corrects).map(Claim::getDate).orElse(on);
    }

    /** The due raised to the floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor.setScale(2) : due;
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.load(sheetId).getToday();
    }

    /** The date, or for none the sheet's entry date, or its today when that is blank. */
    private LocalDate dateOrEntryDate(String sheetId, LocalDate date) {
        if (date != null) {
            return date;
        }
        Sheet sheet = sheets.load(sheetId);
        return sheet.getEntryDate() != null ? sheet.getEntryDate() : sheet.getToday();
    }

    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * The external reference a claim is to carry: stripped, or null when blank. Refused when another claim of the
     * sheet than the one excluded (null for none) carries it, comparing without regard to case.
     */
    private String refOf(String sheetId, String ref, Long excluded) {
        String stripped = ref == null ? "" : ref.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (claims.refInUse(sheetId, stripped, excluded)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reference " + stripped + " is already used on the sheet.");
        }
        return stripped;
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.strip();
    }

    /** Every tag ever used on a claim of the sheet, sorted. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        return tags.findBySheetIdOrderByTagAsc(sheetId).stream().map(ClaimTag::getTag).toList();
    }

    /** A claim's stored tags as a list, sorted; empty for none. */
    public static List<String> tagList(String stored) {
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split(","));
    }

    /** The tags a claim is to carry: each trimmed and lower-cased, repeats and blanks dropped, sorted and comma separated; null for none. */
    private static String tagsOf(String tagged) {
        if (tagged == null) {
            return null;
        }
        TreeSet<String> each = new TreeSet<>();
        Arrays.stream(tagged.split(",")).map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .forEach(each::add);
        if (each.stream().anyMatch(t -> t.length() > 140)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A tag is at most 140 characters.");
        }
        return each.isEmpty() ? null : String.join(",", each);
    }

    /** Keeps each of the tags as used on the sheet. */
    private void keepTags(String sheetId, String stored) {
        for (String tag : tagList(stored)) {
            if (!tags.existsBySheetIdAndTag(sheetId, tag)) {
                tags.saveAndFlush(new ClaimTag(sheetId, tag));
            }
        }
    }
}
