package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people make on a sheet. */
@Service
public class ClaimService {

    /** The number sequence of claims in no period. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingsService settings;
    private final WatchService watches;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates,
            PeriodRepository periods, SheetService sheets, RollupService rollup, SettingsService settings,
            WatchService watches, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.watches = watches;
        this.audit = audit;
    }

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /**
     * Adds a claim for the person; a missing date takes the sheet's today. Refused when dated in a closed period or
     * after the person's retirement date, and a correction (a negative amount) unless it corrects a settled claim of
     * the person.
     */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        LocalDate on = dateOrToday(sheetId, date);
        refuseInClosedPeriod(sheetId, on);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, personId);
        refuseAfterRetirement(person, on);
        String corrected = correctedNumber(sheetId, personId, amount, corrects);
        Claim claim = new Claim(sheetId, personId, scaled(amount), on, noteOf(note));
        claim.setCorrects(corrected);
        claim.setRoundsOn(roundsOn(sheetId, corrected, on));
        claim.setDueBy(on.plusDays(termsFor(sheetId, person, on)));
        logStatus(by, sheetId, person, claim);
        Claim added = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_ADDED", by, person, added));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Edits a claim; refused unless it is a draft, when it is dated in a closed period, or when the edit would date
     * it in one. An open claim is changed by voiding it and adding a new one.
     */
    @Transactional
    public Claim update(String by, String sheetId, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        LocalDate on = dateOrToday(sheetId, date);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        refuseInClosedPeriod(sheetId, claim.getDate());
        refuseInClosedPeriod(sheetId, on);
        refuseAfterRetirement(person(sheetId, claim.getPersonId()), on);
        String corrected = correctedNumber(sheetId, claim.getPersonId(), amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setRoundsOn(roundsOn(sheetId, corrected, on));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", by, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Moves the claim to the status; a change its status does not allow is refused, as is settling by the user
     * who opened it, and any change to a claim dated in a closed period. A clerk settling a claim whose due is above
     * the clerk limit makes it PENDING instead, for a steward to approve or reject.
     */
    @Transactional
    public Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next) {
        Claim claim = lockedClaim(sheetId, id);
        switch (next) {
            case OPEN -> settings.refuseUnlessMay(sheetId, by, "open");
            case SETTLED -> settings.refuseUnlessMay(sheetId, by, "settle");
            case VOID -> settings.refuseUnlessMay(sheetId, by, "void");
            default -> {
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
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
        if (next == ClaimStatus.OPEN) {
            number(sheetId, claim);
            claim.setOpenedBy(by);
        }
        Person person = person(sheetId, claim.getPersonId());
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, PENDING -> throw new IllegalStateException();
        };
        String tail = "";
        if (next == ClaimStatus.SETTLED) {
            // the due is fixed at the figure a statement bound it to, else at the rate the person's grade has in
            // force on the claim's date, with the late charge it has gained by today; with none it stays live
            BigDecimal figure = claim.isStated() && claim.getStatedDue() != null ? claim.getStatedDue()
                    : liveDue(sheetId, person, claim);
            BigDecimal fixed = claim.isCorrection() ? figure
                    : withLateCharge(figure, claim, sheets.load(sheetId));
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
        claim.setDeferredUntil(until);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DEFERRED", by, person(sheetId, saved.getPersonId()), saved) + " until="
                + until);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Raises the claim's proofs count by one. Refused for a SETTLED or VOID claim, and in a closed period.
     */
    @Transactional
    public Claim addProof(String by, String sheetId, Long id) {
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
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
     * force on the claim's date and the claim has no proof; an n/a due is within any threshold.
     */
    private void refuseWithoutProof(String sheetId, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheets.load(sheetId).proofThresholdOn(claim.getDate());
        if (threshold != null && due != null && due.compareTo(threshold) > 0 && claim.getProofs() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with a due above " + threshold.toPlainString() + " needs a proof first.");
        }
    }

    /**
     * A steward approves a pending claim: it is settled, its due fixed at the live figure it shows now. Refused
     * unless it is pending, unless the user is a steward, for the user who opened it, and in a closed period.
     */
    @Transactional
    public Claim approve(String by, String sheetId, Long id) {
        Claim claim = pendingClaim(by, sheetId, id);
        if (by.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user who opened a claim cannot approve it.");
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
        BigDecimal live = liveDue(sheetId, person, claim);
        BigDecimal fixed = claim.isCorrection() ? live : withLateCharge(live, claim, sheets.load(sheetId));
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
        logStatus(by, sheetId, person(sheetId, claim.getPersonId()), claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REJECTED", by, person(sheetId, saved.getPersonId()), saved)
                + " reason=" + why);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * A user whose role may reassign (a steward by the standing rule) moves a DRAFT, OPEN or PENDING claim to another person of the sheet. It keeps its number and date,
     * loses any figure a statement bound it to (it is live again until its new person's next statement), and is
     * listed as its new person's newest claim. Refused unless the user's role may reassign, for a settled or voided claim,
     * to the person it is already with, in a closed period, and when it is dated after the new person's retirement.
     */
    @Transactional
    public Claim reassign(String by, String sheetId, Long id, Long toPersonId) {
        settings.refuseUnlessMay(sheetId, by, "reassign");
        Claim claim = lockedClaim(sheetId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot move.");
        }
        if (claim.getPersonId().equals(toPersonId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already that person's.");
        }
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        refuseAfterRetirement(to, claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        Claim saved = claims.saveAndFlush(claim);
        claims.relist(saved.getId());
        audit.recordOnCommit(record("CLAIM_REASSIGNED", by, to, saved) + " from=" + from.getName() + " to="
                + to.getName());
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
     * number, is listed as its person's newest. Refused unless the user's role may open a claim, for a claim that is
     * not OPEN or is deferred, for an amount out of range, and in a closed period.
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
        part.setDueBy(claim.getDate().plusDays(termsFor(sheetId, person, claim.getDate())));
        part.setSplitFrom(claim.getNumber());
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

    /** The claim, locked; refused when it is dated in a closed period. */
    private Claim lockedClaim(String sheetId, Long id) {
        // the sheet is locked before the claim, in the order a period closing takes them
        sheets.lock(sheetId);
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseInClosedPeriod(sheetId, claim.getDate());
        return claim;
    }

    /**
     * Gives the claim being opened its number: while the sheet's period numbering from is set and its today is on or
     * after it, the next in the sequence of the period the claim is dated in (X for none), else the sheet's next.
     * The sheet is locked, so two claims opened at once never get the same one.
     */
    private void number(String sheetId, Claim claim) {
        Sheet sheet = sheets.load(sheetId);
        LocalDate from = sheet.getPeriodNumberingFrom();
        if (from == null || sheet.getToday().isBefore(from)) {
            claim.setNumber(String.valueOf(sheets.nextClaimNumber(sheetId)));
            return;
        }
        String sequence = periods.findFirstBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(sheetId,
                claim.getDate(), claim.getDate()).map(Period::getName).orElse(NO_PERIOD);
        Long last = claims.lastInSequence(sheetId, sequence);
        claim.numberIn(sequence, last == null ? 1 : last + 1);
    }

    /**
     * The claim's live due: at the rate its person's grade in force on its date has in force on that date, and at least the sheet's
     * floor in force on its date (a correction has none), rounded as the sheet's policy date decides for it; null (n/a) with none.
     */
    public BigDecimal liveDue(String sheetId, Person person, Claim claim) {
        Sheet sheet = sheets.load(sheetId);
        BigDecimal floor = claim.isCorrection() ? null : sheet.floorOn(claim.getDate());
        RoundingMode rounding = sheet.roundingOn(claim.getRoundsOn());
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                .map(pct -> floored(dueOn(claim.getAmount(), pct, rounding), floor)).orElse(null);
    }

    /**
     * The figure the claim shows and totals: its settled due, else the one a statement bound it to (not while it is
     * pending), else live.
     */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isBound() ? claim.getStatedDue() : liveDue(sheetId, person, claim);
    }

    /**
     * The due the claim shows and totals: its figure, with the late charge an open (or pending) overdue claim has gained; a
     * correction gains none.
     */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figure(sheetId, person, claim);
        return claim.getStatus().isOpen() && !claim.isCorrection()
                ? withLateCharge(figure, claim, sheets.load(sheetId))
                : figure;
    }

    /**
     * The figure (null for n/a) of the claim, with a late charge added for every full 30 days it is overdue (past its
     * due-by) by the sheet's today: for each such boundary (its due-by plus 30, 60, 90 ... days), the sheet's late
     * percent in force on that boundary date of the figure, rounded like its due; a boundary whose percent is 0.00
     * adds nothing. A deferred claim is not overdue. The charge is never bound by a statement: it keeps following the
     * sheet's today and its policy date.
     */
    public static BigDecimal withLateCharge(BigDecimal figure, Claim claim, Sheet sheet) {
        if (figure == null || sheet == null || sheet.getToday() == null || claim.isDeferredOn(sheet.getToday())) {
            return figure;
        }
        long overdue = ChronoUnit.DAYS.between(claim.getDueBy(), sheet.getToday());
        long periods = overdue > 0 ? overdue / 30 : 0;
        RoundingMode rounding = sheet.roundingOn(claim.getRoundsOn());
        BigDecimal due = figure;
        for (long k = 1; k <= periods; k++) {
            BigDecimal latePct = sheet.latePctOn(claim.getDueBy().plusDays(30 * k));
            if (latePct.signum() != 0) {
                due = due.add(dueOn(figure, latePct, rounding));
            }
        }
        return due;
    }

    /**
     * Refuses a change to a claim dated on the date when a closed period contains it. The sheet stays locked until
     * the transaction ends, so a period closing at the same moment is checked in turn.
     */
    private void refuseInClosedPeriod(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        if (periods.existsBySheetIdAndClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is dated in a closed period.");
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

    /** Refuses a claim for the person dated after their retirement date; one on or before it is allowed. */
    private static void refuseAfterRetirement(Person person, LocalDate date) {
        if (person.getRetiredFrom() != null && date.isAfter(person.getRetiredFrom())) {
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

    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.strip();
    }
}
