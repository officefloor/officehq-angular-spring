package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claims on a sheet: listed newest first, added to a person and edited one at a time. A claim
 * added with no date takes the entry date while one is set, else the sheet's today (an edit with none, the sheet's today). Its due-by is fixed when it is added: the terms-th business day
 * after its date (skipping weekends and the sheet's holidays then), with its person's own terms, else the terms in force on that date of its person's grade then (else the
 * sheet's terms in force on it); it does not
 * move when its date or the terms change later. A claim is added as DRAFT and moves through its statuses
 * as {@link ClaimStatus} allows; a change it does not allow is refused. Opening a claim gives it
 * the sheet's next number (or, while the sheet numbers by period, the next within its period), which it
 * keeps when voided and is never given again. Only a DRAFT claim
 * can be edited; an OPEN one is changed by voiding it and adding a new one. Settling it
 * fixes its due at its figure (the due bound by a statement, else its person's grade rate of the
 * moment on the claim's date) with its late charge of the moment frozen in; with no figure it
 * stays live. A steward can re-settle a SETTLED claim at the figure it would have today as an open claim
 * bound by no statement, or void it within 7 days of its settlement: its frozen figure leaves every total
 * and it keeps its number. The user who opened a claim cannot settle it, and a clerk cannot settle one whose
 * due is above the sheet's clerk limit (a steward can). A claim with no proofs whose due is above the proof threshold
 * in force on its date cannot be settled or approved by anyone, and one with an empty note whose due is above the
 * note threshold in force on its date cannot be opened. No claim whose due-by falls inside a closed period can
 * be added, edited (to or from such a date), opened, settled, re-settled or voided. No claim can be added
 * or edited to a date after its person's retirement date. A claim with a negative amount is a
 * correction: it must name, by number, a SETTLED claim of the same person; only a correction may name one.
 * A claim whose due is above the review threshold in force on its date goes to REVIEW when opened, with no
 * number, until a steward reviews it ({@link #review}).
 * A steward can re-date an OPEN or PENDING claim ({@link #redate}). A DRAFT claim can be deleted, and
 * restored to DRAFT on the same sheet's today ({@link #delete}, {@link #restore}).
 */
@Service
public class ClaimService {

    /** How many days after its settlement a steward may still void a SETTLED claim. */
    static final int VOID_SETTLED_DAYS = 7;

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final PersonWatchRepository watches;
    private final HolidayService holidays;
    private final ClaimTagRepository tags;
    private final VoidReasonService voidReasons;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            RollupService rollup, PeriodRepository periods, SettingService settings, PersonWatchRepository watches,
            HolidayService holidays, ClaimTagRepository tags, VoidReasonService voidReasons, Audit audit) {
        this.voidReasons = voidReasons;
        this.tags = tags;
        this.watches = watches;
        this.holidays = holidays;
        this.settings = settings;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Every tag ever used on a claim of the sheet, sorted. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        return tags.findBySheetIdOrderByTagAsc(sheetId).stream().map(ClaimTag::getTag).toList();
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, List.of());
    }

    /**
     * Add a claim carrying an external reference (null: none) and tags (as {@link Claim#tagsOf} reads them);
     * a reference already on the sheet is refused.
     */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tagged) {
        // The snapshot locks the sheet first, so the person is read as retired by any retirement before.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = dateOrEntryDate(sheetId, date);
        int terms = termsOf(sheetId, person, on);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        refuseIfClosed(sheetId, dueBy);
        if (!person.mayClaimOn(on)) throw new PersonRetiredException();
        refuseUnlessCorrects(sheetId, personId, null, amount, corrects);
        refuseIfRefUsed(sheetId, null, ref);
        Claim draft = new Claim(sheetId, personId, amount, on, note, dueBy, terms);
        draft.setCorrects(corrects);
        draft.setRef(ref);
        draft.setTags(tagged);
        draft.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        Claim claim = claims.saveAndFlush(draft);
        logStatus(sheetId, user, person, claim);
        claim = claims.saveAndFlush(claim);
        record("CLAIM_ADDED", sheetId, user, person, claim, claim.getNumber(),
                (claim.getRef() != null ? " ref=" + claim.getRef() : "")
                + (claim.getTags().isEmpty() ? "" : " tags=" + String.join(",", claim.getTags())));
        keepTags(sheetId, claim);
        if (claim.isLateEntry()) record("LATE_ENTRY", sheetId, user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim edit(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tagged) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimStatusRefusedException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (claim.isLockedAgainst(user)) throw new ClaimLockedException();
        LocalDate on = dateOrToday(sheetId, date);
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        if (!person.mayClaimOn(on)) throw new PersonRetiredException();
        refuseUnlessCorrects(sheetId, person.getId(), claim.getId(), amount, corrects);
        refuseIfRefUsed(sheetId, claim.getId(), ref);
        if (!claim.getLines().isEmpty() && amount.compareTo(claim.getAmount()) != 0) {
            throw new ClaimLinedAmountException();
        }
        claim.setAmount(amount);
        claim.setRef(ref);
        claim.setTags(tagged);
        claim.setCorrects(corrects);
        claim.setDate(on);
        claim.setNote(note);
        claim.setLockedBy(null);
        claim.setEditedBy(user.strip());
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        keepTags(sheetId, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Open a claim's editor: the claim is locked for every other user until the editor is saved or
     * cancelled, or the sheet's today changes. A claim that cannot be edited (not DRAFT, or due inside a
     * closed period), or one another user's editor already locks, is refused and takes no lock.
     */
    @Transactional
    public Claim lock(String sheetId, String user, Long id) {
        // Locking the sheet first, so two users opening the same claim's editor at once take turns.
        sheets.lockForNumbering(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) throw new ClaimStatusRefusedException();
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.isLockedAgainst(user)) throw new ClaimLockedException();
        claim.setLockedBy(user.strip());
        return claims.saveAndFlush(claim);
    }

    /** Cancel a claim's editor: the user's own lock on it is released; another user's is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, Long id) {
        sheets.lockForNumbering(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getLockedBy() == null || claim.isLockedAgainst(user)) return claim;
        claim.setLockedBy(null);
        return claims.saveAndFlush(claim);
    }

    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus next) {
        return changeStatus(sheetId, user, id, next, null);
    }

    /**
     * Settle a claim as part of a bulk settle: by the rules of settling one, then given the bulk's
     * batch number, the sheet's next one when the bulk has none yet (its first claim settled).
     */
    @Transactional
    public Claim settleInBatch(String sheetId, String user, Long id, Integer batch) {
        Claim claim = changeStatus(sheetId, user, id, ClaimStatus.SETTLED, null);
        claim.setSettleBatch(batch != null ? batch : sheets.lockForNumbering(sheetId).nextSettleBatch());
        return claims.saveAndFlush(claim);
    }

    /** Void a claim; while the sheet's list of void reasons is not empty, only with a reason on it. */
    @Transactional
    public Claim voidClaim(String sheetId, String user, Long id, String reason) {
        return changeStatus(sheetId, user, id, ClaimStatus.VOID, reason);
    }

    private Claim changeStatus(String sheetId, String user, Long id, ClaimStatus next, String reason) {
        // Opening takes the sheet's next number, locking the sheet first so the claim is read only
        // once no other open on the sheet is under way; a refusal rolls the number back.
        Sheet numbering = next == ClaimStatus.OPEN ? sheets.lockForNumbering(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        String action = switch (next) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            default -> throw new ClaimStatusRefusedException();
        };
        if (!settings.mayDo(sheetId, user, action)) throw new ClaimNotPermittedException();
        boolean voidingSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidingSettled) refuseVoidOfSettled(sheetId, user, claim);
        else if (!claim.getStatus().canBecome(next)) {
            throw new ClaimStatusRefusedException();
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimDeferredException();
        }
        if (next == ClaimStatus.SETTLED && claim.isOpenedOrEditedBy(user)) {
            throw new ClaimSettledByOpenerException();
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        // Settling freezes the late charge of the moment into the settled due.
        BigDecimal settling = next == ClaimStatus.SETTLED ? chargedFigureOf(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED) refuseUnproved(sheetId, person, claim, settling);
        BigDecimal opening = next == ClaimStatus.OPEN ? chargedFigureOf(sheetId, person, claim) : null;
        if (next == ClaimStatus.OPEN) refuseUnnoted(sheetId, claim, opening);
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, settling)) next = ClaimStatus.PENDING;
        String voidReason = next == ClaimStatus.VOID ? voidReasons.reasonForVoid(sheetId, reason) : null;
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        refuseIfClosed(sheetId, claim.getDueBy());
        if (next == ClaimStatus.OPEN) refuseOverCap(sheetId, claim);
        if (next == ClaimStatus.OPEN && overReviewThreshold(sheetId, claim, opening)) next = ClaimStatus.REVIEW;
        switch (next) {
            case OPEN -> {
                number(numbering, claim);
                claim.setOpenedBy(user.strip());
            }
            case REVIEW -> {
                claim.setOpenedBy(user.strip());
                claim.setReviewOn(sheets.today(sheetId));
            }
            case SETTLED -> {
                claim.setSettledDue(settling);
                claim.setSettleBatch(null);
            }
            case PENDING -> claim.setSettleBatch(null);
            case VOID -> claim.setVoidReason(voidReason);
            default -> {}
        }
        claim.setStatus(next);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case REVIEW -> "CLAIM_REVIEW";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalStateException();
        };
        String due = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString())
                : voidingSettled ? " reversed=" + figureText(saved.getSettledDue())
                : "";
        if (voidReason != null) due += " reason=" + voidReason;
        record(event, sheetId, user, person, saved, saved.getNumber(), due);
        if (next == ClaimStatus.SETTLED) notifyWatchers(sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Delete a DRAFT claim: DELETED, it counts nowhere and cannot be edited, opened or voided until restored.
     * Refused for a claim in any other status, one another user's editor locks, or one due inside a closed period.
     */
    @Transactional
    public Claim delete(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().canBecome(ClaimStatus.DELETED)) throw new ClaimStatusRefusedException();
        if (claim.isLockedAgainst(user)) throw new ClaimLockedException();
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.setLockedBy(null);
        claim.setStatus(ClaimStatus.DELETED);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_DELETED", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Restore a DELETED claim to DRAFT, on the same sheet's today it was deleted. Refused for a claim in any
     * other status, on a later day, or for one due inside a closed period.
     */
    @Transactional
    public Claim restore(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DELETED) throw new ClaimStatusRefusedException();
        if (!sheets.today(sheetId).equals(claim.deletedOn())) throw new ClaimRestoreWindowException();
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.setStatus(ClaimStatus.DRAFT);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_RESTORED", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward other than its opener or last editor approves a PENDING claim: SETTLED, its due fixed at its live due now. */
    @Transactional
    public Claim approve(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = pendingForSteward(sheetId, user, id);
        if (claim.isOpenedOrEditedBy(user)) throw new ClaimApprovedByOpenerException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDueBy());
        BigDecimal settling = chargedFigureOf(sheetId, person, claim);
        refuseUnproved(sheetId, person, claim, settling);
        claim.setSettledDue(settling);
        claim.setStatus(ClaimStatus.SETTLED);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber(), " due="
                + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString()));
        notifyWatchers(sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward reviews a REVIEW claim: OPEN, given the sheet's next number, still opened by the user who
     * opened it. Refused for anyone else, a claim in any other status, or one due inside a closed period.
     */
    @Transactional
    public Claim review(String sheetId, String user, Long id) {
        // Numbering locks the sheet first, so the claim is read once no other change is under way.
        Sheet numbering = sheets.lockForNumbering(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.REVIEW) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseOverCap(sheetId, claim);
        number(numbering, claim);
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REVIEWED", sheetId, user, person, saved, null, "");
        record("CLAIM_OPENED", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward rejects a PENDING claim with a reason: OPEN again, bound by its person's last statement if it was. */
    @Transactional
    public Claim reject(String sheetId, String user, Long id, String reason) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = pendingForSteward(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDueBy());
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REJECTED", sheetId, user, person, saved, saved.getNumber(), " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-settles a SETTLED claim: its due fixed again at the figure it would have today as an
     * open claim bound by no statement (the rate, grade, floor and late percent in force on its date, the
     * late charge as of today). Refused for anyone
     * else, a claim in any other status, or one due inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.SETTLED) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDueBy());
        BigDecimal old = claim.getSettledDue();
        BigDecimal figure = floored(sheetId, claim, LateCharge.withChargeAsOpen(claim, dueOf(sheetId, person, claim),
                sheets.today(sheetId), graceOf(sheetId, person, claim), d -> settings.latePctOn(sheetId, d),
                roundingOf(sheetId, claim)));
        claim.setSettledDue(figure);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber(),
                " old=" + figureText(old) + " new=" + figureText(figure));
        notifyWatchers(sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure bound by a
     * statement stays bound; its period, which follows its due-by, stays too. While the sheet numbers by
     * period and the claim was numbered within another period, it takes the next number of its due-by's
     * period, its old number never given again. Refused for anyone else, a claim in any other status, one
     * due inside a closed period, or a new date after its person's retirement date.
     */
    @Transactional
    public Claim redate(String sheetId, String user, Long id, LocalDate date) {
        // Numbering locks the sheet first, so the claim is read once no other change is under way.
        Sheet numbering = sheets.lockForNumbering(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().isOpen()) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        if (!person.mayClaimOn(date)) throw new PersonRetiredException();
        LocalDate old = claim.getDate();
        String oldPeriod = claim.getNumberPeriod() != null ? claim.getNumberPeriod() : periodOn(sheetId, claim.getDueBy());
        claim.setDate(date);
        if (numbering.numbersByPeriod() && !oldPeriod.equals(periodOn(sheetId, claim.getDueBy()))) {
            if (claim.getNumberPeriod() != null) {
                claims.retireNumberWithin(sheetId, claim.getNumberPeriod(), claim.getNumberSeq());
            }
            number(numbering, claim);
        }
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REDATED", sheetId, user, person, saved, saved.getNumber(), " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Refuse voiding a SETTLED claim unless by a steward within {@value #VOID_SETTLED_DAYS} days of its
     * settlement (its status log's last SETTLED date) by the sheet's today.
     */
    private void refuseVoidOfSettled(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        LocalDate settled = claim.settledOn();
        LocalDate today = sheets.today(sheetId);
        if (settled == null || today.isBefore(settled) || today.isAfter(settled.plusDays(VOID_SETTLED_DAYS))) {
            throw new ClaimVoidWindowException();
        }
    }

    /** One NOTIFY line per user watching the claim's person, in the order they started watching. */
    private void notifyWatchers(String sheetId, String user, Person person, Claim claim) {
        for (PersonWatch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user.strip() + " user=" + watch.getUserName()
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    /** A fixed due as an audit line has it: two decimals, or n/a when none. */
    private static String figureText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2).toPlainString();
    }

    /**
     * A user whose role may reassign (a steward, unless the permission matrix says otherwise) moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its date
     * and takes the next number in the sequence it would be numbered in (the sheet's, or while the sheet
     * numbers by period, its due-by's period's), its old number never given again (a DRAFT, having none,
     * gets none), loses any figure a statement bound it to (live again until its new person's next
     * statement), and is listed as its new person's newest claim. A SETTLED or VOID claim cannot move,
     * nor one due inside a closed period, nor one dated after its new person's retirement date.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId) {
        return reassign(sheetId, user, id, toPersonId, false);
    }

    /**
     * Reassign a claim by the rules above; a bulk move ({@code bulk}) takes a claim already its new
     * person's as moved to them again rather than refusing it.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId, boolean bulk) {
        // Numbering locks the sheet first, so the claim is read once no other change is under way.
        Sheet numbering = sheets.lockForNumbering(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        ClaimStatus status = claim.getStatus();
        if (status != ClaimStatus.DRAFT && !status.isOpen()) throw new ClaimStatusRefusedException();
        if (!settings.mayDo(sheetId, user, "reassign")) throw new ClaimNotPermittedException();
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(toPersonId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!bulk && from.getId().equals(to.getId())) throw new ClaimReassignRefusedException();
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!to.mayClaimOn(claim.getDate())) throw new PersonRetiredException();
        claim.unstate(sheets.today(sheetId));
        claim.moveTo(to.getId(), claims.nextListed());
        if (claim.getNumber() != null) {
            if (claim.getNumberPeriod() != null) {
                claims.retireNumberWithin(sheetId, claim.getNumberPeriod(), claim.getNumberSeq());
            }
            number(numbering, claim);
        }
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A split claim as it now is, and the new claim split off it. */
    public record Split(Claim original, Claim split) {}

    /**
     * Split an amount (more than 0.00 and less than its amount) off an OPEN claim, by a user whose role may
     * open a claim. The claim keeps the rest of its amount and loses any figure a statement bound it to; the
     * new claim is OPEN with the split-off amount, the same date and note, the sheet's next number, opened
     * by the user, with its due-by fixed now from the terms in force on the date, and names the original's
     * number as split from, a late entry when its due-by falls in a soft-closed period. Refused for a claim
     * not OPEN, a deferred one, or one due inside a closed period (judged by the original alone).
     */
    @Transactional
    public Split split(String sheetId, String user, Long id, BigDecimal amount) {
        // Numbering locks the sheet first, so the claim is read once no other change is under way.
        Sheet numbering = sheets.lockForNumbering(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.OPEN) throw new ClaimStatusRefusedException();
        if (claim.isDeferredOn(sheets.today(sheetId))) throw new ClaimDeferredException();
        if (!settings.mayDo(sheetId, user, "open")) throw new ClaimNotPermittedException();
        refuseIfClosed(sheetId, claim.getDueBy());
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) throw new ClaimSplitRefusedException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = claim.getDate();
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unstate(sheets.today(sheetId));
        Claim original = claims.saveAndFlush(claim);
        int terms = termsOf(sheetId, person, on);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        Claim split = new Claim(sheetId, person.getId(), amount, on, original.getNote(), dueBy, terms);
        split.setSplitFrom(original.getNumber());
        split.setTags(original.getTags());
        split.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        number(numbering, split);
        split.setOpenedBy(user.strip());
        split.setEditedBy(original.getEditedBy());
        split.setStatus(ClaimStatus.OPEN);
        split = claims.saveAndFlush(split);
        logStatus(sheetId, user, person, split);
        split = claims.saveAndFlush(split);
        record("CLAIM_SPLIT", sheetId, user, person, original, original.getNumber(), " into=" + split.getNumber());
        record("CLAIM_OPENED", sheetId, user, person, split);
        rollup.recordChanges(sheetId, user, before);
        return new Split(original, split);
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the claim
     * counts in no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for a
     * claim that is not OPEN, a date not after today, or a claim due inside a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, Long id, LocalDate until) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.OPEN) throw new ClaimStatusRefusedException();
        if (!until.isAfter(sheets.today(sheetId))) throw new ClaimDeferDateException();
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.deferUntil(until, sheets.today(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_DEFERRED", sheetId, user, person, saved, saved.getNumber(), " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Raise a claim's proofs count by one. Refused for a SETTLED, VOID or DELETED claim, or one due inside a closed period.
     */
    @Transactional
    public Claim addProof(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ClaimStatusRefusedException();
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_PROOF_ADDED", sheetId, user, person, saved, saved.getNumber(), " proofs=" + saved.getProofs());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Add a line to a DRAFT claim: its amount becomes its lines' quantities times their unit amounts added
     * up, and the claim counts as edited by the user. Refused for a claim in any other status, one due
     * inside a closed period, or one another user's editor locks.
     */
    @Transactional
    public Claim addLine(String sheetId, String user, Long id, String label, int qty, BigDecimal unit) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) throw new ClaimStatusRefusedException();
        if (claim.isLockedAgainst(user)) throw new ClaimLockedException();
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.addLine(label, qty, unit);
        claim.setEditedBy(user.strip());
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_LINE_ADDED", sheetId, user, person, saved, saved.getNumber(),
                " label=" + label + " qty=" + qty + " unit=" + unit.setScale(2).toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Comment on a claim: the comment is kept with the user and the sheet's today, in any status. */
    @Transactional
    public Claim comment(String sheetId, String user, Long id, String text) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        claim.comment(user.strip(), sheets.today(sheetId), text);
        Claim saved = claims.saveAndFlush(claim);
        record("COMMENT_ADDED", sheetId, user, person, saved, saved.getNumber(), " text=" + text);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Remind an OPEN or PENDING claim: a REMIND line is written and the claim is reminded on the sheet's
     * today. Refused for a claim in any other status, or one already reminded on the sheet's today. When the
     * sheet has an escalation gap and the previous reminder was at least that many days before today, an
     * ESCALATE line follows, once per claim ever.
     */
    @Transactional
    public Claim remind(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().isOpen()) throw new ClaimStatusRefusedException();
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) throw new ClaimRemindedException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        Integer escalateAfter = settings.escalateAfter(sheetId);
        boolean escalates = escalateAfter != null && !claim.isEscalated() && claim.getRemindedOn() != null
                && !claim.getRemindedOn().plusDays(escalateAfter).isAfter(today);
        claim.remind(today);
        if (escalates) claim.escalate();
        Claim saved = claims.saveAndFlush(claim);
        record("REMIND", sheetId, user, person, saved);
        if (escalates) record("ESCALATE", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Refuse settling or approving a claim with no proofs whose due is above the proof threshold in force on
     * its date (its person's grade's then when set, else the sheet's); a due of n/a is within any, and no
     * threshold means none is needed.
     */
    private void refuseUnproved(String sheetId, Person person, Claim claim, BigDecimal due) {
        if (claim.getProofs() > 0 || due == null) return;
        BigDecimal threshold = settings.proofThresholdFor(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) throw new ClaimProofRequiredException();
    }

    /**
     * Refuse opening a claim with an empty note whose due is above the note threshold in force on its date;
     * a due of n/a is within any, and no threshold means no note is needed.
     */
    private void refuseUnnoted(String sheetId, Claim claim, BigDecimal due) {
        if (due == null || (claim.getNote() != null && !claim.getNote().isBlank())) return;
        BigDecimal threshold = settings.noteThresholdOn(sheetId, claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) throw new ClaimNoteRequiredException();
    }

    /**
     * Whether a claim being opened goes to REVIEW: its due is above the review threshold in force on its
     * date; a due of n/a is within any, and no threshold means none goes to review.
     */
    private boolean overReviewThreshold(String sheetId, Claim claim, BigDecimal due) {
        if (due == null) return false;
        BigDecimal threshold = settings.reviewThresholdOn(sheetId, claim.getDate());
        return threshold != null && due.compareTo(threshold) > 0;
    }

    /** The PENDING claim a steward approves or rejects; refused when not PENDING or not a steward. */
    private Claim pendingForSteward(String sheetId, String user, Long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.PENDING) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        return claim;
    }

    /**
     * Give an opened claim its number: FY&lt;year&gt;-&lt;n&gt;, the next in the fiscal year its due-by falls in
     * (by the sheet's fiscal start month) after the last given in that year anywhere in the sheet's tree
     * (its topmost ancestor and every descendant of that), or while the sheet numbers by period, the next
     * within the period its due-by falls in (in no period: the next in the sheet's X sequence).
     */
    private void number(Sheet sheet, Claim claim) {
        if (!sheet.numbersByPeriod()) {
            int year = fiscalYearOf(sheet, claim.getDueBy() != null ? claim.getDueBy() : claim.getDate());
            claim.setFiscalNumber(year, sheets.nextFiscalNumberInTree(sheet.getId(), year));
            return;
        }
        String period = periodOn(sheet.getId(), claim.getDueBy());
        claim.setNumberWithin(period, claims.lastNumberWithin(sheet.getId(), period) + 1);
    }

    /** The fiscal year the date falls in, by the calendar year of its first day (the sheet's start month). */
    private static int fiscalYearOf(Sheet sheet, LocalDate date) {
        return date.getMonthValue() >= sheet.getFiscalStartMonth() ? date.getYear() : date.getYear() - 1;
    }

    /**
     * The terms a claim of the person dated on the date is fixed with: their own terms in force then, else
     * their grade's then. Its due-by is the terms-th business day after its date (skipping weekends and the sheet's holidays).
     */
    private int termsOf(String sheetId, Person person, LocalDate on) {
        Integer own = person.termsOn(on);
        return own != null ? own : settings.termsFor(sheetId, person.gradeOn(on), on);
    }

    /**
     * Refuse to open a claim whose due-by falls in a period with a cap when its person already has that
     * many OPEN or PENDING claims due inside the period.
     */
    private void refuseOverCap(String sheetId, Claim claim) {
        Period period = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.contains(claim.getDueBy()))
                .findFirst().orElse(null);
        if (period == null || period.getCap() == null) return;
        long open = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId()).stream()
                .filter(c -> !c.getId().equals(claim.getId()))
                .filter(c -> c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.PENDING)
                .filter(c -> period.contains(c.getDueBy()))
                .count();
        if (open >= period.getCap()) throw new ClaimCapReachedException();
    }

    /** The name of the sheet's period the date falls in; empty when in none. */
    private String periodOn(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.contains(date))
                .map(Period::getName)
                .findFirst().orElse("");
    }

    /**
     * A claim's figure now: the due fixed when it was settled, else the rate figure bound by its person's
     * last statement, else its rate figure (no floor, no charge); null when it has none.
     */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        return dueOf(sheetId, person, claim);
    }

    /**
     * A claim's due now: the greater of the floor in force on its date and its figure with the late
     * charge on top while it is overdue (a correction: neither); a settled due as fixed; null when it
     * has none. A statement binds the figure, never the charge nor the floor.
     */
    public BigDecimal chargedFigureOf(String sheetId, Person person, Claim claim) {
        return charged(sheetId, person, claim, figureOf(sheetId, person, claim));
    }

    /** What a rate preview finds: how many claims' dues would change, and by how much in all. */
    public record RatePreview(int count, BigDecimal diff) {}

    /**
     * How many OPEN and PENDING claims of people on the grade (the one in force on the claim's date) would show
     * a different due were the percent the grade's rate on every date, and the sum of new minus current dues
     * (a due of n/a counts as 0). A claim bound by a statement does not change. Nothing is stored.
     */
    @Transactional(readOnly = true)
    public RatePreview previewRate(String sheetId, String grade, BigDecimal pct) {
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        Map<Long, Person> byId = new HashMap<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) byId.put(p.getId(), p);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (!claim.getStatus().isOpen() || claim.isBound()) continue;
            Person person = byId.get(claim.getPersonId());
            if (person == null || !grade.equals(person.gradeOn(claim.getDate()))) continue;
            BigDecimal current = chargedFigureOf(sheetId, person, claim);
            BigDecimal figure = claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, roundingOf(sheetId, claim));
            BigDecimal next = charged(sheetId, person, claim, figure);
            BigDecimal was = current == null ? BigDecimal.ZERO.setScale(2) : current;
            BigDecimal now = next == null ? BigDecimal.ZERO.setScale(2) : next;
            if (current == null || now.compareTo(was) != 0) {
                count++;
                diff = diff.add(now.subtract(was));
            }
        }
        return new RatePreview(count, diff.setScale(2));
    }

    /** The claim's due with the figure given: floored, with its late charge while overdue; a settled due as fixed. */
    private BigDecimal charged(String sheetId, Person person, Claim claim, BigDecimal figure) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        return floored(sheetId, claim, LateCharge.withCharge(claim, figure, sheets.today(sheetId),
                graceOf(sheetId, person, claim), d -> settings.latePctOn(sheetId, d), roundingOf(sheetId, claim)));
    }

    /** The grace days of the person's grade on the claim's date, in force on it; 0 when none. */
    private int graceOf(String sheetId, Person person, Claim claim) {
        return settings.graceOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
    }

    /** The due at least the sheet's floor in force on the claim's date, unless a correction; null when none. */
    private BigDecimal floored(String sheetId, Claim claim, BigDecimal due) {
        if (due == null || claim.isCorrection()) return due;
        return due.max(settings.floorOn(sheetId, claim.getDate()));
    }

    /** How the claim's live figures round: by its date, or a correction's by its corrected claim's ({@link Rounding}). */
    private RoundingMode roundingOf(String sheetId, Claim claim) {
        LocalDate basis = Rounding.basisOf(claim,
                Rounding.datesByNumber(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId())));
        return Rounding.of(basis, settings.roundingOn(sheetId, basis), settings.policyDate(sheetId), sheets.today(sheetId));
    }

    /**
     * A claim's rate figure: its amount times the rate on its date of its person's grade in force on it (of those
     * that apply to the claim: no forward-only rate saved after it was added), rounded to cents as {@link Rounding}
     * has it, with no floor; null with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        RoundingMode rounding = roundingOf(sheetId, claim);
        return rates.onFor(sheetId, person.gradeOn(claim.getDate()), claim.getDate(), claim.getAdded())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .orElse(null);
    }

    /** Log the status the claim now has: by the user on the sheet's today, with its due at this moment. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.logStatus(user.strip(), sheets.today(sheetId), chargedFigureOf(sheetId, person, claim));
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    /** A claim's audit line; one about a claim with a number has it, then any extra fields. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, String number,
            String extra) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip() + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + extra);
    }

    /**
     * Take a person's claims, as they arrive with them from another sheet, onto the sheet (locked for
     * numbering by the caller): each loses what {@link Claim#transferTo} drops, and each that had a number
     * takes the sheet's next, in the order they were opened (by the sheet's today each was first opened on,
     * then in the order they were added). A correction, or a claim split off another, then names the
     * number its claim was given. Their tags are kept among those used on the sheet.
     */
    @Transactional
    public void receive(Sheet to, List<Claim> arriving) {
        Map<Long, String> old = new HashMap<>();
        for (Claim claim : arriving) {
            old.put(claim.getId(), claim.getNumber());
            claim.transferTo(to.getId(), claims.nextAdded());
        }
        claims.saveAllAndFlush(arriving);
        numberAfresh(to, arriving, old);
        for (Claim claim : arriving) keepTags(to.getId(), claim);
        claims.saveAllAndFlush(arriving);
    }

    /**
     * Number afresh, on the sheet (locked for numbering by the caller), the claims that had a number before
     * ({@code oldNumbers}, by claim id): each takes the sheet's next, in the order they were opened (by the
     * sheet's today each was first opened on, then in the order they were added). A correction, or a claim
     * split off another, then names the number its claim was given.
     */
    @Transactional
    public void numberAfresh(Sheet to, List<Claim> numbering, Map<Long, String> oldNumbers) {
        List<Claim> numbered = numbering.stream().filter(c -> oldNumbers.get(c.getId()) != null)
                .sorted(Comparator.comparing(Claim::openedOn,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Claim::getId))
                .toList();
        Map<String, String> renumbered = new HashMap<>();
        for (Claim claim : numbered) {
            number(to, claim);
            claims.saveAndFlush(claim);
            renumbered.put(oldNumbers.get(claim.getId()), claim.getNumber());
        }
        for (Claim claim : numbering) {
            if (claim.getCorrects() != null) {
                claim.setCorrects(renumbered.getOrDefault(claim.getCorrects(), claim.getCorrects()));
            }
            if (claim.getSplitFrom() != null) {
                claim.setSplitFrom(renumbered.getOrDefault(claim.getSplitFrom(), claim.getSplitFrom()));
            }
        }
        claims.saveAllAndFlush(numbering);
    }

    /** Keep each of the claim's tags among those ever used on the sheet. */
    private void keepTags(String sheetId, Claim claim) {
        keepTags(sheetId, claim.getTags());
    }

    /** Keep each of the tags (a claim's or a person's) among those ever used on the sheet. */
    @Transactional
    public void keepTags(String sheetId, List<String> tagged) {
        for (String tag : tagged) {
            if (!tags.existsBySheetIdAndTag(sheetId, tag)) tags.save(new ClaimTag(sheetId, tag));
        }
    }

    /** Refuse a reference (null: none) that a claim on the sheet other than this one (null: any) already carries. */
    private void refuseIfRefUsed(String sheetId, Long self, String ref) {
        if (ref == null) return;
        claims.findBySheetIdAndRefKey(sheetId, Claim.refKeyOf(ref))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> { throw new DuplicateClaimRefException(); });
    }

    /**
     * Refuse a correction that names no SETTLED claim of its person by number, and a claim that names
     * one but is not a correction. A correction may not exceed what remains of the claim it corrects: its
     * amount less the corrections already made against it (VOID or DELETED ones, and this claim (null:
     * none) itself, left out).
     */
    private void refuseUnlessCorrects(String sheetId, Long personId, Long self, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) throw new CorrectionRefusedException();
            return;
        }
        if (corrects == null) throw new CorrectionRefusedException();
        List<Claim> ofPerson = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Claim target = ofPerson.stream()
                .filter(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED)
                .findFirst().orElseThrow(CorrectionRefusedException::new);
        BigDecimal remaining = ofPerson.stream()
                .filter(c -> corrects.equals(c.getCorrects()) && !c.getId().equals(self)
                        && c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .map(Claim::getAmount)
                .reduce(target.getAmount(), BigDecimal::add);
        if (amount.negate().compareTo(remaining) > 0) throw new CorrectionRefusedException();
    }

    /** Whether a clerk settles a claim whose due is above the clerk limit; a due of n/a is within any. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        if (settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due != null && due.compareTo(limit) > 0;
    }

    /** Refuse a change to a claim whose due-by falls inside a closed period; the caller holds the sheet's lock. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new PeriodClosedException();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    /** A new claim's date: the one given, else the entry date while set, else the sheet's today. */
    public LocalDate dateOrEntryDate(String sheetId, LocalDate date) {
        if (date != null) return date;
        LocalDate entryDate = settings.entryDate(sheetId);
        return entryDate != null ? entryDate : sheets.today(sheetId);
    }
}
