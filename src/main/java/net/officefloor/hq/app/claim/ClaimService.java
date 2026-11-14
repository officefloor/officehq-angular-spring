package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonTermsService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.PersonRetirement;
import net.officefloor.hq.app.person.PersonRetirementRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.setting.Permission;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on a sheet make. */
@Service
public class ClaimService {

    /** Written for a due that has no rate to work from. */
    private static final String NO_RATE = "n/a";

    /** How many days after its settlement a steward may still void a SETTLED claim. */
    static final int VOID_WINDOW_DAYS = 7;

    /** The name a claim's period number takes when its date falls in no period. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimCommentRepository comments;
    private final ClaimLineRepository lines;
    private final PersonRepository people;
    private final RateService rates;
    private final PersonGradeService grades;
    private final PersonTermsService personTerms;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final PersonWatchRepository watches;
    private final PersonRetirementRepository retirements;
    private final HolidayService holidays;
    private final VoidReasonService voidReasons;
    private final History history;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimLogRepository logs, PersonRepository people, RateService rates,
            PersonGradeService grades, SheetService sheets, PeriodRepository periods, SettingService settings, PersonWatchRepository watches,
            HolidayService holidays, VoidReasonService voidReasons, ClaimCommentRepository comments, PersonRetirementRepository retirements,
            ClaimLineRepository lines, PersonTermsService personTerms, History history, Audit audit) {
        this.history = history;
        this.personTerms = personTerms;
        this.lines = lines;
        this.retirements = retirements;
        this.comments = comments;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.periods = periods;
        this.watches = watches;
        this.settings = settings;
        this.claims = claims;
        this.logs = logs;
        this.people = people;
        this.rates = rates;
        this.grades = grades;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's entry date, else its today. One added while the period
     *  its due-by falls in is soft-closed is a late entry, and one whose due-by falls in a closed period is refused.
     *  Its due-by is fixed now: the
     *  business day after its date that is the terms in force on the day it is added (the sheet's today): the
     *  person's own, else those of the grade the person had that day, else the sheet's, counted skipping weekends and the sheet's holidays now. A negative amount is a
     *  correction and must name a SETTLED claim of the person it corrects. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    /** Add a claim as above, carrying an external reference (blank: none); one already used on the
     *  sheet, without regard to case, is refused. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /** Add a claim as above, carrying the tags typed comma separated (blank: none); see
     *  {@link #tagsOf}. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Person person = person(sheetId, personId);
        corrects = numberOf(corrects);
        LocalDate on = date != null ? date : settings.defaultClaimDate(sheetId);
        int terms = termsFor(sheetId, person, sheets.today(sheetId));
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        refuseClosed(sheetId, dueBy);
        refuseRetired(person, on);
        refuseBadCorrection(sheetId, personId, amount, corrects, null);
        ref = refOf(ref);
        refuseRefInUse(sheetId, ref, null);
        Claim claim = new Claim(sheetId, personId, scaled(amount), on, dueBy, terms, noteOf(note), corrects);
        claim.setRef(ref);
        claim.setTags(tagsOf(tags));
        boolean late = periods.softClosedOn(sheetId, dueBy);
        if (late) {
            claim.markLateEntry();
        }
        Claim added = claims.saveAndFlush(claim);
        useTags(sheetId, added);
        log(sheetId, user, person, added);
        audit.recordOnCommit(line("CLAIM_ADDED", sheetId, user, person, added, added.getNumber())
                + (added.getRef() != null ? " ref=" + added.getRef() : "")
                + (!added.getTags().isEmpty() ? " tags=" + String.join(",", added.getTags()) : ""));
        if (late) {
            record("LATE_ENTRY", sheetId, user, person, added);
        }
        return added;
    }

    /** Whether the claim form would add a claim for the person dated on a day: the person is on the
     *  sheet, the claim's due-by falls in no closed period and the person was not retired then. */
    public boolean accepts(String sheetId, Long personId, LocalDate date) {
        Optional<Person> found = people.findByIdAndSheetId(personId, sheetId);
        if (found.isEmpty()) {
            return false;
        }
        Person person = found.get();
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, date, termsFor(sheetId, person, sheets.today(sheetId)));
        try {
            refuseClosed(sheetId, dueBy);
            refuseRetired(person, date);
            return true;
        } catch (PeriodClosedException | PersonRetiredException e) {
            return false;
        }
    }

    /** Change a DRAFT claim's amount, date, note and external reference (blank: none; one another
     *  claim on the sheet already uses, without regard to case, is refused); a missing date takes the
     *  sheet's entry date, else its today. Any
     *  other claim is refused: an OPEN one is changed by voiding it and adding a new one. */
    @Transactional
    public Claim update(String sheetId, String user, Long personId, Long id, BigDecimal amount, LocalDate date,
            String note, String corrects, String ref, String tags) {
        Person person = person(sheetId, personId);
        corrects = numberOf(corrects);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(claim.getStatus());
        }
        if (claim.lockedAgainst(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
        LocalDate on = date != null ? date : settings.defaultClaimDate(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(person, on);
        refuseBadCorrection(sheetId, personId, amount, corrects, claim.getId());
        ref = refOf(ref);
        refuseRefInUse(sheetId, ref, claim.getId());
        refuseRelined(claim, scaled(amount));
        claim.setAmount(scaled(amount));
        claim.setRef(ref);
        claim.setTags(tagsOf(tags));
        claim.setCorrects(corrects);
        claim.setDate(on);
        claim.setNote(noteOf(note));
        claim.setLockedBy(null);
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        useTags(sheetId, saved);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        return saved;
    }

    /** Change a DRAFT claim's amount as the claim form would, keeping its date, note, correction,
     *  reference and tags, writing CLAIM_EDITED. A claim in any other status, or one the form would
     *  refuse, is passed over and keeps its amount: null then. */
    @Transactional
    public Claim updateAmount(String sheetId, String user, Long id, BigDecimal amount) {
        Optional<Claim> found = claims.findById(id).filter(c -> c.getSheetId().equals(sheetId));
        if (found.isEmpty() || found.get().getStatus() != ClaimStatus.DRAFT) {
            return null;
        }
        Claim claim = found.get();
        try {
            return update(sheetId, user, claim.getPersonId(), id, amount, claim.getDate(), claim.getNote(),
                    claim.getCorrects(), claim.getRef(), String.join(",", claim.getTags()));
        } catch (ClaimNotEditableException | ClaimLockedException | PeriodClosedException
                | PersonRetiredException | CorrectionRefusedException | RefInUseException
                | LinedAmountException e) {
            return null;
        }
    }

    /** Add a line to a DRAFT claim, counting as an edit of it: its amount becomes the quantities times
     *  the unit amounts of its lines added up, and CLAIM_LINE_ADDED is written ending with the line. A
     *  claim that is not a DRAFT, a correction, one due by a day in a closed period, one another user's
     *  editor locks, a blank label, a quantity below 1 or a negative unit amount, is refused. */
    @Transactional
    public Claim addLine(String sheetId, String user, Long personId, Long id, String label, int qty,
            BigDecimal unit) {
        Person person = person(sheetId, personId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(claim.getStatus());
        }
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.lockedAgainst(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
        if (claim.isCorrection()) {
            throw new LinedAmountException("A correction takes no lines");
        }
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty() || qty < 1 || unit.signum() < 0) {
            throw new LinedAmountException("A line needs a label, a quantity of at least 1 and a unit amount");
        }
        ClaimLine added = lines.save(new ClaimLine(claim.getId(), trimmed, qty, scaled(unit)));
        claim.setAmount(lines.findByClaimIdOrderByIdAsc(claim.getId()).stream().map(ClaimLine::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.UNNECESSARY));
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_LINE_ADDED", sheetId, user, person, saved, saved.getNumber())
                + " label=" + added.getLabel() + " qty=" + added.getQty() + " unit=" + added.getUnit().toPlainString());
        return saved;
    }

    /** A claim's lines, oldest first, each as <label> <qty> x <unit>. */
    public List<String> linesOf(Long claimId) {
        return lines.findByClaimIdOrderByIdAsc(claimId).stream().map(ClaimLine::line).toList();
    }

    /** The lines of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<String>> linesOf(String sheetId) {
        return lines.ofSheet(sheetId).stream().collect(Collectors.groupingBy(ClaimLine::getClaimId,
                Collectors.mapping(ClaimLine::line, Collectors.toList())));
    }

    /** A claim with lines keeps the amount they add up to: an edit typing another is refused. */
    private void refuseRelined(Claim claim, BigDecimal amount) {
        if (claim.getAmount().compareTo(amount) != 0 && !lines.findByClaimIdOrderByIdAsc(claim.getId()).isEmpty()) {
            throw new LinedAmountException("A claim with lines takes its amount from them");
        }
    }

    /** Open a claim's editor: lock the claim to the user until they save or cancel it, or the
     *  sheet's today changes. A claim that is not a DRAFT, is dated in a closed period, or is locked
     *  by another user's editor is refused and takes no lock. */
    @Transactional
    public Claim lock(String sheetId, String user, Long personId, Long id) {
        person(sheetId, personId);
        // Lock the sheet first, so two users opening the editor at the same moment take turns.
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(claim.getStatus());
        }
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.lockedAgainst(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel a claim's editor: release the user's lock on it; a lock another user holds is kept. */
    @Transactional
    public Claim unlock(String sheetId, String user, Long personId, Long id) {
        person(sheetId, personId);
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** Release every editor's lock on the sheet's claims: the sheet's today changed. */
    @Transactional
    public void releaseLocks(String sheetId) {
        claims.releaseLocks(sheetId);
    }

    /** Make the claims settled together one settle batch: take the sheet's next batch number and
     *  mark each with it. */
    @Transactional
    public long batch(String sheetId, List<Claim> settled) {
        long batch = sheets.nextBatchNumber(sheetId);
        claims.markBatch(settled.stream().map(Claim::getId).toList(), batch);
        settled.forEach(claim -> claim.setBatch(batch));
        return batch;
    }

    /** Move a claim to a new status; a change its status does not allow, or one the sheet's
     *  permission matrix does not let the user's role make, is refused, as is the user who opened a
     *  claim settling it. A clerk settling a claim whose due is above the clerk limit
     *  leaves it PENDING a steward's approval instead. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        return changeStatus(sheetId, user, personId, id, to, null);
    }

    /** Move a claim to a new status, as above; a void, while the sheet's list of void reasons is not
     *  empty, needs a reason chosen from it, which the claim keeps and the audit line ends with. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to,
            String voidReason) {
        return changeStatus(sheetId, user, personId, id, to, voidReason, false);
    }

    /** Refuse the user settling part of a claim when settling the claim itself would be refused by the
     *  four-eyes rules (whoever opened or last edited it cannot) or the proof rule (its due now). */
    @Transactional
    public void refusePartSettle(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (claim.openedOrEditedBy(user)) {
            throw new OwnClaimSettleRefusedException(user);
        }
        refuseUnproved(sheetId, person, claim, dueOf(sheetId, person, claim));
    }

    /** Settle a claim as of a date on or before the sheet's today (null: as of now), by the rules of
     *  settling it: the due frozen is the claim's due as it stood at the end of that day, that day
     *  standing in for the sheet's today, and CLAIM_SETTLED ends with on=<date>. A date after the
     *  sheet's today, or one the claim had not been added by, is refused. */
    @Transactional
    public Claim settle(String sheetId, String user, Long personId, Long id, LocalDate on) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.SETTLED, null, false, on);
    }

    /** Settle a claim just split off another for the user, as settling it would, save that the
     *  four-eyes rules and the proof rule were judged on the claim it was split from. */
    @Transactional
    public Claim settleSplitOff(String sheetId, String user, Long personId, Long id) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.SETTLED, null, true);
    }

    private Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to,
            String voidReason, boolean judgedOnOriginal) {
        return changeStatus(sheetId, user, personId, id, to, voidReason, judgedOnOriginal, null);
    }

    private Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to,
            String voidReason, boolean judgedOnOriginal, LocalDate settledOn) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, so a claim opened at the same moment elsewhere waits and the claim
        // read below is its latest state.
        if (to == ClaimStatus.OPEN || to == ClaimStatus.SETTLED) {
            sheets.lock(sheetId);
        }
        Claim claim = claim(sheetId, personId, id);
        settings.refuseUnless(sheetId, user, switch (to) {
            case OPEN -> Permission.OPEN;
            case SETTLED -> Permission.SETTLE;
            case VOID -> Permission.VOID;
            case DRAFT, PENDING, DELETED, REVIEW -> throw new StatusChangeRefusedException(claim.getStatus(), to);
        });
        // While the person's flag is in force only a steward opens their claims.
        if (to == ClaimStatus.OPEN && person.flaggedOn(sheets.today(sheetId))
                && settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PermissionRefusedException(user, Permission.OPEN);
        }
        refuseClosed(sheetId, claim.getDueBy());
        // A SETTLED claim is voided only by a steward, within the void window of its settlement.
        boolean reversing = to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (reversing) {
            refuseLateOrUnstewardedVoid(sheetId, user, claim);
        } else if (!claim.getStatus().canMoveTo(to)) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        String reason = voidReason == null || voidReason.isBlank() ? null : voidReason.trim();
        if (to == ClaimStatus.VOID) {
            refuseUnreasonedVoid(sheetId, reason);
        }
        if (to == ClaimStatus.SETTLED && !judgedOnOriginal && claim.openedOrEditedBy(user)) {
            throw new OwnClaimSettleRefusedException(user);
        }
        if (to == ClaimStatus.SETTLED && claim.deferredOn(sheets.today(sheetId))) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        // Taken while the claim is still OPEN, so a settled due freezes in the late charge it has
        // gathered by then.
        BigDecimal due = to == ClaimStatus.SETTLED ? settleDueOf(sheetId, person, claim, settledOn) : null;
        if (to == ClaimStatus.SETTLED && !judgedOnOriginal) {
            refuseUnproved(sheetId, person, claim, due);
        }
        if (to == ClaimStatus.OPEN) {
            refuseUnnoted(sheetId, person, claim);
            refuseOverCap(sheetId, claim);
            refuseOverMaxOpen(sheetId, claim);
        }
        if (to == ClaimStatus.SETTLED && overClerkLimit(sheetId, person, user, due)) {
            to = ClaimStatus.PENDING;
        }
        if (to == ClaimStatus.OPEN && overReviewThreshold(sheetId, person, claim)) {
            to = ClaimStatus.REVIEW;
        }
        claim.setStatus(to);
        if (to == ClaimStatus.SETTLED || to == ClaimStatus.PENDING) {
            // Settled one at a time until a bulk settle marks it with its batch.
            claim.setBatch(null);
        }
        if (to == ClaimStatus.OPEN || to == ClaimStatus.REVIEW) {
            // Numbered only once it is settled.
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
            numberOnSettle(sheetId, claim);
        } else if (to == ClaimStatus.VOID) {
            claim.setVoidReason(reason);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case REVIEW -> "CLAIM_REVIEW";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalStateException("A claim is deleted or restored on its own");
        };
        String line = line(event, sheetId, user, person, saved, saved.getNumber());
        if (to == ClaimStatus.SETTLED) {
            line += " due=" + settledDueText(saved);
            if (settledOn != null) {
                line += " on=" + settledOn;
            }
        } else if (reversing) {
            line += " reversed=" + settledDueText(saved);
        }
        if (to == ClaimStatus.VOID && reason != null) {
            line += " reason=" + reason;
        }
        audit.recordOnCommit(line);
        if (to == ClaimStatus.SETTLED) {
            notifyWatchers(sheetId, user, person, saved);
        }
        return saved;
    }

    /** The due a claim is settled at: as it stands now, or (a date given) as it stood at the end of
     *  that day; a date after the sheet's today, or one the claim had not been added by, is refused. */
    private BigDecimal settleDueOf(String sheetId, Person person, Claim claim, LocalDate on) {
        if (on == null) {
            return dueOf(sheetId, person, claim);
        }
        if (on.isAfter(sheets.today(sheetId))) {
            throw new StatusChangeRefusedException(claim.getStatus(), ClaimStatus.SETTLED);
        }
        Claim then = history.claim(claim.getId(), on)
                .orElseThrow(() -> new StatusChangeRefusedException(claim.getStatus(), ClaimStatus.SETTLED));
        return dueOf(sheetId, person, then, on);
    }

    /** Delete a DRAFT claim: it is DELETED, counts nowhere and cannot be edited, opened or voided;
     *  its status log gains a DELETED line and CLAIM_DELETED is written. Any other claim, one dated in
     *  a closed period, or one another user's editor locks, is refused. */
    @Transactional
    public Claim delete(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new DeleteRefusedException("A " + claim.getStatus() + " claim cannot be deleted");
        }
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.lockedAgainst(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
        claim.setLockedBy(null);
        claim.setStatus(ClaimStatus.DELETED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        record("CLAIM_DELETED", sheetId, user, person, saved);
        return saved;
    }

    /** Restore a DELETED claim to a DRAFT, on the sheet's today it was deleted: its status log gains a
     *  DRAFT line and CLAIM_RESTORED is written. Any other claim, a later day, or a claim dated in a
     *  closed period, is refused. */
    @Transactional
    public Claim restore(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (claim.getStatus() != ClaimStatus.DELETED) {
            throw new DeleteRefusedException("A " + claim.getStatus() + " claim cannot be restored");
        }
        LocalDate deletedOn = logs.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .filter(entry -> entry.getStatus() == ClaimStatus.DELETED)
                .map(ClaimLogEntry::getOn)
                .reduce((first, last) -> last)
                .orElse(null);
        if (!sheets.today(sheetId).equals(deletedOn)) {
            throw new DeleteRefusedException("A claim is restored only on the day it was deleted");
        }
        refuseClosed(sheetId, claim.getDueBy());
        claim.setStatus(ClaimStatus.DRAFT);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        record("CLAIM_RESTORED", sheetId, user, person, saved);
        return saved;
    }

    /** Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it
     *  the claim counts in no total, is not overdue, cannot be settled and no statement binds it. A claim a
     *  statement binds is deferred only once the binding ends: until then it counts and can be settled. A
     *  claim that is not OPEN, a date not after today, or a claim dated in a closed period, is refused. */
    @Transactional
    public Claim defer(String sheetId, String user, Long personId, Long id, LocalDate until) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new DeferRefusedException("A " + claim.getStatus() + " claim cannot be deferred");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new DeferRefusedException("A claim is deferred to a date after the sheet's today");
        }
        claim.setDeferredUntil(until);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_DEFERRED", sheetId, user, person, saved, saved.getNumber())
                + " until=" + until);
        return saved;
    }

    /** A split: the claim that kept the rest of its amount, and the claim split off it. */
    public record Split(Claim kept, Claim added) {
    }

    /** Split an amount off an OPEN claim: it keeps the rest of its amount and loses any figure a
     *  statement bound it to, and a new OPEN claim of the split-off amount, with the same date and
     *  note and no number (one is given at settlement), opened by the user and due by its date plus the terms in force on it,
     *  is listed under the person naming the claim it was split from (a late entry when its due-by falls
     *  in a soft-closed period). A claim that is not OPEN, one
     *  deferred, one dated in a closed period, an amount not above 0.00 and below the claim's, or a
     *  user whose role may not open a claim, is refused. */
    @Transactional
    public Split split(String sheetId, String user, Long personId, Long id, BigDecimal amount) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        settings.refuseUnless(sheetId, user, Permission.OPEN);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN || claim.deferredOn(sheets.today(sheetId))) {
            throw new SplitRefusedException("A " + claim.getStatus() + " claim cannot be split");
        }
        BigDecimal part = scaled(amount);
        if (part.signum() <= 0 || part.compareTo(claim.getAmount()) >= 0) {
            throw new SplitRefusedException("A claim is split by an amount above 0.00 and below its own");
        }
        claim.setAmount(claim.getAmount().subtract(part));
        claim.unbind();
        Claim kept = claims.saveAndFlush(claim);
        LocalDate on = kept.getDate();
        int terms = termsFor(sheetId, person, sheets.today(sheetId));
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        Claim split = Claim.splitOff(kept, part, dueBy, terms, null, user);
        if (periods.softClosedOn(sheetId, dueBy)) {
            split.markLateEntry();
        }
        Claim added = claims.saveAndFlush(split);
        log(sheetId, user, person, added);
        audit.recordOnCommit(line("CLAIM_SPLIT", sheetId, user, person, kept, kept.getNumber())
                + " into=" + (added.getNumber() != null ? added.getNumber() : ""));
        record("CLAIM_OPENED", sheetId, user, person, added);
        return new Split(kept, added);
    }

    /** A steward approves a PENDING claim: it is SETTLED, its due frozen at this moment. Anyone else,
     *  the user who opened or last edited it, or a claim that is not PENDING, is refused. */
    @Transactional
    public Claim approve(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, as settling does: the claim takes the next number.
        sheets.lock(sheetId);
        Claim claim = pending(sheetId, user, person, id, ClaimStatus.SETTLED);
        if (claim.openedOrEditedBy(user)) {
            throw new OwnClaimSettleRefusedException(user);
        }
        BigDecimal due = dueOf(sheetId, person, claim);
        refuseUnproved(sheetId, person, claim, due);
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        numberOnSettle(sheetId, claim);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber())
                + " due=" + settledDueText(saved));
        notifyWatchers(sheetId, user, person, saved);
        return saved;
    }

    /** A steward reviews a REVIEW claim: it is OPEN, unnumbered until it is settled, and
     *  keeps who opened it; CLAIM_REVIEWED then CLAIM_OPENED are written. Anyone else, a claim that is
     *  not in REVIEW, or one dated in a closed period, is refused. */
    @Transactional
    public Claim review(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new StatusChangeRefusedException(claim.getStatus(), ClaimStatus.OPEN);
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ApprovalRefusedException(user);
        }
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        record("CLAIM_REVIEWED", sheetId, user, person, saved, null);
        record("CLAIM_OPENED", sheetId, user, person, saved);
        return saved;
    }

    /** A steward assigns a REVIEW claim's review to a user, trimmed, in place of anyone assigned before;
     *  REVIEW_ASSIGNED is written. Anyone else, a claim in any other status, or a blank user, is refused. */
    @Transactional
    public Claim assignReview(String sheetId, String user, Long personId, Long id, String assignee) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ReviewAssignForbiddenException(user);
        }
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ReviewAssignRefusedException("A " + claim.getStatus() + " claim's review cannot be assigned");
        }
        if (assignee == null || assignee.isBlank()) {
            throw new ReviewAssignRefusedException("A review is assigned to a user");
        }
        claim.setReviewAssignee(assignee.trim());
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("REVIEW_ASSIGNED", sheetId, user, person, saved, saved.getNumber())
                + " user=" + saved.getReviewAssignee());
        return saved;
    }

    /** A steward waives an OPEN or PENDING claim's late charge: it bears none from then on, whatever
     *  its overdue state, even one due by a day in a closed period, and CLAIM_WAIVED is written. Anyone
     *  else, a claim in any other status, or one already waived, is refused. */
    @Transactional
    public Claim waive(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new WaiveForbiddenException(user);
        }
        if (!claim.getStatus().isOpen()) {
            throw new WaiveRefusedException("A " + claim.getStatus() + " claim's late charge cannot be waived");
        }
        if (claim.isWaived()) {
            throw new WaiveRefusedException("The claim's late charge is already waived");
        }
        claim.waive();
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_WAIVED", sheetId, user, person, saved);
        return saved;
    }

    /** Hold an OPEN or PENDING claim, or release it when it is held: it still counts in every total,
     *  and CLAIM_HELD or CLAIM_RELEASED is written. A claim
     *  in any other status, or one dated in a closed period, is refused. */
    @Transactional
    public Claim toggleHold(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (!claim.getStatus().isOpen()) {
            throw new HoldRefusedException("A " + claim.getStatus() + " claim cannot be held or released");
        }
        refuseClosed(sheetId, claim.getDueBy());
        claim.toggleHeld();
        Claim saved = claims.saveAndFlush(claim);
        record(saved.isHeld() ? "CLAIM_HELD" : "CLAIM_RELEASED", sheetId, user, person, saved);
        return saved;
    }

    /** Share a claim with another person on the sheet at a share percent (two decimals, more than
     *  0.00): CLAIM_SHARED is written ending with the holder and their share. The claim's own person,
     *  a person already holding a share, shares totalling more than 100.00, a SETTLED, VOID or DELETED
     *  claim, or one dated in a closed period, is refused. */
    @Transactional
    public Claim share(String sheetId, String user, Long personId, Long id, Long withPersonId, BigDecimal share) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        Person holder = person(sheetId, withPersonId);
        ClaimStatus status = claim.getStatus();
        if (status == ClaimStatus.SETTLED || status == ClaimStatus.VOID || status == ClaimStatus.DELETED) {
            throw new ShareRefusedException("A " + status + " claim cannot be shared");
        }
        refuseClosed(sheetId, claim.getDueBy());
        if (holder.getId().equals(person.getId())) {
            throw new ShareRefusedException("A claim cannot be shared with its own person");
        }
        if (share.signum() <= 0 || share.scale() > 2) {
            throw new ShareRefusedException("A share must be more than 0.00, with two decimals");
        }
        List<ClaimShare> held = claim.getShares();
        if (held.stream().anyMatch(s -> s.personId().equals(holder.getId()))) {
            throw new ShareRefusedException(holder.getName() + " already holds a share of the claim");
        }
        BigDecimal total = held.stream().map(ClaimShare::share).reduce(share, BigDecimal::add);
        if (total.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ShareRefusedException("The shares of a claim cannot total more than 100.00");
        }
        BigDecimal scaledShare = share.setScale(2);
        claim.addShare(new ClaimShare(holder.getId(), scaledShare, sheets.today(sheetId)));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_SHARED", sheetId, user, person, saved, saved.getNumber())
                + " with=" + holder.getName() + " share=" + scaledShare.toPlainString());
        return saved;
    }

    /** Remind an OPEN or PENDING claim: REMIND is written and the claim's reminded date is the sheet's
     *  today. A claim in any other status, or one already reminded on the sheet's today, is refused. When
     *  the sheet has an escalation gap and the claim's previous reminder was at least that many days before
     *  the sheet's today, ESCALATE is written after REMIND, once per claim ever. */
    @Transactional
    public Claim remind(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, so two presses on the same day write one reminder.
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        LocalDate today = sheets.today(sheetId);
        if (!claim.getStatus().isOpen()) {
            throw new RemindRefusedException("A " + claim.getStatus() + " claim cannot be reminded");
        }
        if (today.equals(claim.getRemindedOn())) {
            throw new RemindRefusedException("The claim was already reminded on " + today);
        }
        LocalDate previous = claim.getRemindedOn();
        Integer gap = settings.escalateAfter(sheetId);
        boolean escalate = gap != null && previous != null && !claim.isEscalated()
                && !previous.plusDays(gap).isAfter(today);
        claim.setRemindedOn(today);
        if (escalate) {
            claim.setEscalated(true);
        }
        Claim saved = claims.saveAndFlush(claim);
        record("REMIND", sheetId, user, person, saved);
        if (escalate) {
            record("ESCALATE", sheetId, user, person, saved);
        }
        return saved;
    }

    /** Add a proof of a kind (receipt or photo; null when none is chosen, a receipt) to a claim: its
     *  proofs count, and its count of the kind, rise by one. A SETTLED or VOID claim, one dated in a
     *  closed period, or a kind other than receipt or photo, is refused. */
    @Transactional
    public Claim addProof(String sheetId, String user, Long personId, Long id, String kind) {
        if (kind != null && !ProofKind.valid(kind)) {
            throw new ProofRefusedException(kind);
        }
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ProofRefusedException(claim.getStatus());
        }
        claim.addProof(kind);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_PROOF_ADDED", sheetId, user, person, saved, saved.getNumber())
                + " proofs=" + saved.getProofs() + (kind != null ? " kind=" + kind : ""));
        return saved;
    }

    /** Comment on a claim in any status: the comment is kept with the user and the sheet's today, and
     *  COMMENT_ADDED is written ending with its text. A blank comment is refused. */
    @Transactional
    public Claim addComment(String sheetId, String user, Long personId, Long id, String text) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new CommentRefusedException("A comment cannot be empty");
        }
        comments.save(new ClaimComment(sheetId, claim.getId(), user, sheets.today(sheetId), trimmed));
        audit.recordOnCommit(line("COMMENT_ADDED", sheetId, user, person, claim, claim.getNumber())
                + " text=" + trimmed);
        return claim;
    }

    /** A claim's comments, oldest first, each as <user> on <date>: <text>. */
    public List<String> commentsOf(Long claimId) {
        return comments.findByClaimIdOrderByIdAsc(claimId).stream().map(ClaimComment::line).toList();
    }

    /** The comments on the sheet's claims by claim id, each oldest first, as they stood at the end of a
     *  day: those written while the sheet's today was on or before it (null: every one). */
    public Map<Long, List<String>> commentsOf(String sheetId, LocalDate asOf) {
        return comments.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(comment -> asOf == null || !comment.getOn().isAfter(asOf))
                .collect(Collectors.groupingBy(ClaimComment::getClaimId,
                        Collectors.mapping(ClaimComment::line, Collectors.toList())));
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, bound again by the person's
     *  last statement if that statement bound it. Anyone else, or a claim that is not PENDING, is
     *  refused. */
    @Transactional
    public Claim reject(String sheetId, String user, Long personId, Long id, String reason) {
        Person person = person(sheetId, personId);
        Claim claim = pending(sheetId, user, person, id, ClaimStatus.OPEN);
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_REJECTED", sheetId, user, person, saved, saved.getNumber())
                + " reason=" + reason.trim());
        return saved;
    }

    /** A steward re-settles a SETTLED claim: its settled due is frozen again at the figure it would
     *  have today as an OPEN claim bound by no statement (today's rates, grades, floor and late
     *  charge), and its status log gains a SETTLED line. Anyone else, a claim that is not SETTLED, or
     *  one dated in a closed period, is refused. */
    @Transactional
    public Claim resettle(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResettleRefusedException(user);
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new StatusChangeRefusedException(claim.getStatus(), ClaimStatus.SETTLED);
        }
        refuseClosed(sheetId, claim.getDueBy());
        String old = settledDueText(claim);
        BigDecimal figure = liveFigureOf(sheetId, person, claim, null, null);
        claim.setSettledDue(figure == null ? null : flooredDue(sheetId, person, claim, figure, true, null));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber())
                + " old=" + old + " new=" + settledDueText(saved));
        notifyWatchers(sheetId, user, person, saved);
        return saved;
    }

    /** A user whose role the sheet's permission matrix lets reassign (by the standing rule a steward)
     *  moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its date, takes
     *  the next number in the sequence it is numbered in (its old one given up for good; a draft has
     *  none and gets none), loses any figure a statement bound it to (live again until its new person's
     *  next statement), and is listed as its new person's newest claim. Anyone else, a SETTLED or VOID
     *  claim, a correction (it names a claim of its own person), a claim dated in a closed period, or
     *  a move to the same person or to one retired before the claim's date, is refused. */
    @Transactional
    public Claim reassign(String sheetId, String user, Long personId, Long id, Long toPersonId) {
        return move(sheetId, user, personId, id, toPersonId, false);
    }

    /** Move a claim selected among others to a person, by the rules of {@link #reassign}, except that
     *  a selected claim already the person's is moved too: listed again as their newest. */
    @Transactional
    public Claim reassignSelected(String sheetId, String user, Long personId, Long id, Long toPersonId) {
        return move(sheetId, user, personId, id, toPersonId, true);
    }

    private Claim move(String sheetId, String user, Long personId, Long id, Long toPersonId, boolean samePerson) {
        Person from = person(sheetId, personId);
        Person to = person(sheetId, toPersonId);
        // Lock the sheet first, as opening does: a numbered claim takes the next number.
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (!settings.allows(sheetId, user, Permission.REASSIGN)) {
            throw new ReassignRefusedException(user);
        }
        if (!claim.getStatus().canReassign()) {
            throw new MoveRefusedException("A " + claim.getStatus() + " claim cannot move to another person");
        }
        if (!samePerson && from.getId().equals(to.getId())) {
            throw new MoveRefusedException("The claim is already " + to.getName() + "'s");
        }
        if (claim.isCorrection()) {
            throw new MoveRefusedException("A correction stays with the person whose claim it corrects");
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(to, claim.getDate());
        claim.setPersonId(to.getId());
        if (claim.getNumber() != null) {
            String old = claim.getNumber();
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
            claims.retireNumber(sheetId, old);
        }
        claim.unbind();
        claims.saveAndFlush(claim);
        claims.relist(claim.getId());
        Claim saved = claim(sheetId, to.getId(), id);
        audit.recordOnCommit(line("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        return saved;
    }

    /** A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed and any figure a
     *  statement bound it to stays bound; its period, which follows its due-by, and its number
     *  stay too. Anyone but a steward, a claim that is not OPEN or PENDING, a claim whose due-by falls
     *  in a closed period, or a new date after its person's retirement, is refused. */
    @Transactional
    public Claim redate(String sheetId, String user, Long personId, Long id, LocalDate date) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, as opening does: the claim may take the next number.
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new RedateForbiddenException(user);
        }
        if (!claim.getStatus().isOpen()) {
            throw new RedateRefusedException("A " + claim.getStatus() + " claim cannot be re-dated");
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(person, date);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_REDATED", sheetId, user, person, saved, saved.getNumber())
                + " old=" + old + " new=" + date);
        return saved;
    }

    /** A steward sets a DRAFT, OPEN or PENDING claim's due-by directly. It stays as set: a holiday added
     *  later does not move it; its overdue, late charge, grace and period follow it. Anyone but a
     *  steward, a claim in any other status, or an old or new due-by in a closed period, is refused. */
    @Transactional
    public Claim setDueBy(String sheetId, String user, Long personId, Long id, LocalDate dueBy) {
        Person person = person(sheetId, personId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new DueBySetForbiddenException(user);
        }
        if (!claim.getStatus().canReassign()) {
            throw new DueBySetRefusedException("A " + claim.getStatus() + " claim cannot have its due-by set");
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseClosed(sheetId, dueBy);
        LocalDate old = claim.getDueBy();
        claim.setDueBy(dueBy);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_DUE_BY_SET", sheetId, user, person, saved, saved.getNumber())
                + " old=" + old + " new=" + dueBy);
        return saved;
    }

    /** A claim's status log, oldest first: one line per status it has had. */
    public List<String> logOf(Long claimId) {
        return logs.findByClaimIdOrderByIdAsc(claimId).stream().map(ClaimLogEntry::text).toList();
    }

    /** The status logs of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<String>> logsOf(String sheetId) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream().collect(Collectors.groupingBy(
                ClaimLogEntry::getClaimId, Collectors.mapping(ClaimLogEntry::text, Collectors.toList())));
    }

    /** The status logs of the sheet's claims by claim id, each oldest first, as they stood at the end
     *  of a day: the lines logged while the sheet's today was on or before it (null: every line). */
    public Map<Long, List<String>> logsOf(String sheetId, LocalDate asOf) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(entry -> asOf == null || !entry.getOn().isAfter(asOf))
                .collect(Collectors.groupingBy(ClaimLogEntry::getClaimId,
                        Collectors.mapping(ClaimLogEntry::text, Collectors.toList())));
    }

    /** The status each of the sheet's claims had at the end of a day, by claim id, as its status log
     *  records it: the last status it moved to on or before that day. A claim added after it has none. */
    public Map<Long, ClaimStatus> statusesAsOf(String sheetId, LocalDate day) {
        Map<Long, ClaimStatus> statuses = new HashMap<>();
        for (ClaimLogEntry entry : logs.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!entry.getOn().isAfter(day)) {
                statuses.put(entry.getClaimId(), entry.getStatus());
            }
        }
        return statuses;
    }

    /** Log the status a claim has just moved to (or been added in): by whom, on the sheet's today, and
     *  its due as it is now. */
    private void log(String sheetId, String user, Person person, Claim claim) {
        BigDecimal due = dueOf(sheetId, person, claim);
        logs.save(new ClaimLogEntry(sheetId, claim.getId(), claim.getStatus(), user, sheets.today(sheetId),
                due != null ? due.toPlainString() : NO_RATE));
    }

    /** A SETTLED claim is voided only by a steward, and only while the sheet's today is within
     *  {@value #VOID_WINDOW_DAYS} days of the date its status log last records it SETTLED. */
    /** While the sheet's list of void reasons is not empty, a void needs a reason from it; a reason
     *  not on the list is refused whatever the list holds. */
    private void refuseUnreasonedVoid(String sheetId, String reason) {
        if (reason == null ? !voidReasons.list(sheetId).isEmpty() : !voidReasons.listed(sheetId, reason)) {
            throw new VoidRefusedException("A void needs a reason from the sheet's list");
        }
    }

    private void refuseLateOrUnstewardedVoid(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new VoidRefusedException(user + " is not a steward: only a steward voids a settled claim");
        }
        LocalDate settledOn = logs.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .filter(entry -> entry.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLogEntry::getOn)
                .reduce((first, last) -> last)
                .orElseThrow(() -> new VoidRefusedException("Claim " + claim.getId() + " has no settlement"));
        if (sheets.today(sheetId).isAfter(settledOn.plusDays(VOID_WINDOW_DAYS))) {
            throw new VoidRefusedException("A settled claim is voided within " + VOID_WINDOW_DAYS
                    + " days of its settlement on " + settledOn);
        }
    }

    /** The PENDING claim a steward, or the person's approver, approves or rejects; refused when it is
     *  not PENDING, dated in a closed period, or the user is neither a steward nor the person's approver. */
    private Claim pending(String sheetId, String user, Person person, Long id, ClaimStatus to) {
        Claim claim = claim(sheetId, person.getId(), id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD && !user.equals(person.getApprover())) {
            throw new ApprovalRefusedException(user);
        }
        return claim;
    }

    private Claim claim(String sheetId, Long personId, Long id) {
        return claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
    }

    private static String settledDueText(Claim claim) {
        return claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : NO_RATE;
    }

    /** The number a claim brought onto the sheet takes, as one opened now would: the sheet's next
     *  number for its due-by. Taken with the sheet locked. */
    public String numberFor(String sheetId, LocalDate dueBy) {
        return nextNumber(sheetId, dueBy);
    }

    /** The number a claim opened now takes: under period numbering (set, and the sheet's today on or
     *  after it) the next within the period its due-by falls in, <period>-<n>, or X-<n> when it falls
     *  in none; otherwise the sheet's next sheet-wide number in the fiscal year of its due-by,
     *  FY<year>-<n>. Taken with the sheet locked. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!periodNumbering(sheetId)) {
            return sheets.nextClaimNumber(sheetId, date);
        }
        String prefix = periodPrefixOf(sheetId, date);
        long last = Stream.concat(claims.numbers(sheetId).stream(), claims.retiredNumbers(sheetId).stream())
                .filter(number -> number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(n -> n.matches("\\d{1,18}"))
                .mapToLong(Long::parseLong)
                .max().orElse(0);
        return prefix + (last + 1);
    }

    /** A claim being settled takes the next number in its sequence for its due-by, unless it was
     *  numbered already (as claims opened before numbering on settlement were). Taken with the sheet locked. */
    private void numberOnSettle(String sheetId, Claim claim) {
        if (claim.getNumber() == null) {
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
        }
    }

    /** Whether period numbering is on: set, and the sheet's today on or after it. */
    private boolean periodNumbering(String sheetId) {
        LocalDate from = settings.periodNumberingFrom(sheetId);
        return from != null && !sheets.today(sheetId).isBefore(from);
    }

    /** The prefix a period number of a claim due by a date takes: <period>- or X-. */
    private String periodPrefixOf(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(period -> period.contains(date))
                .findFirst().map(Period::getName).orElse(NO_PERIOD) + "-";
    }

    /** The figure a claim shows: the due fixed when it was settled, else the due its person's last
     *  statement bound it to (late charge included), else its due at the person's grade rate; null when it has none. */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        return figureOf(sheetId, person, claim, null);
    }

    /** The figure a claim (as it stood at the end of a day) showed then, the sheet's grades and rates
     *  as they stood then (null: the figure it shows now). */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim, LocalDate asOf) {
        return figureOf(sheetId, person, claim, asOf, null);
    }

    private BigDecimal figureOf(String sheetId, Person person, Claim claim, LocalDate asOf, RateOverride override) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue()
                : liveFigureOf(sheetId, person, claim, asOf, override);
    }

    /** The due a claim shows: a settled claim's frozen due; else the due its person's last statement
     *  bound it to; otherwise its floored figure (the greater of the sheet's floor in force on its date
     *  and its figure; none for a correction) plus the late charge on that floored figure (none while it
     *  is deferred: it is not overdue then); null when it has no figure. */
    public BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        return dueOf(sheetId, person, claim, null);
    }

    /** The due a claim (as it stood at the end of a day) showed then: that day stands in for the
     *  sheet's today, and the floor, late percent, grades and rates are as they stood then (null: the
     *  due it shows now). */
    public BigDecimal dueOf(String sheetId, Person person, Claim claim, LocalDate asOf) {
        return dueOf(sheetId, person, claim, asOf, null);
    }

    /** The due a claim would show were a grade's rate the one given on every date (null: as the rates
     *  stand). */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim, LocalDate asOf, RateOverride override) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        // A statement binds the due as shown: the floored figure, late charge included.
        if (claim.isStated()) {
            return claim.getStatedDue();
        }
        BigDecimal figure = figureOf(sheetId, person, claim, asOf, override);
        boolean open = claim.getStatus().isOpen() && !claim.deferredOn(todayOr(sheetId, asOf));
        return figure == null ? null : flooredDue(sheetId, person, claim, figure, open, asOf);
    }

    /** A figure raised to the floor (see floored), plus the late charge worked out on that floored figure. */
    private BigDecimal flooredDue(String sheetId, Person person, Claim claim, BigDecimal figure, boolean open,
            LocalDate asOf) {
        BigDecimal floored = floored(sheetId, claim, figure, asOf);
        return floored.add(lateChargeOf(sheetId, person, claim, floored, open, asOf));
    }

    /** The day that stands for the sheet's today: the given one, else the sheet's today. */
    private LocalDate todayOr(String sheetId, LocalDate asOf) {
        return asOf != null ? asOf : sheets.today(sheetId);
    }

    /** A figure raised to the sheet's floor in force on the claim's date, unless the claim is a correction. */
    private BigDecimal floored(String sheetId, Claim claim, BigDecimal figure, LocalDate asOf) {
        return claim.isCorrection() ? figure : figure.max(settings.floor(sheetId, claim.getDate(), asOf));
    }

    /** The date a claim is overdue after: its due-by plus the grace days in force on its date for the
     *  person's grade in force on its date (as they stood at the end of a day; null: as they stand now). */
    public LocalDate overdueFrom(String sheetId, Person person, Claim claim, LocalDate asOf) {
        String grade = grades.gradeOn(person.getId(), claim.getDate(), asOf);
        return claim.getDueBy().plusDays(settings.graceFor(sheetId, grade, claim.getDate(), asOf));
    }

    /** The late charge on a claim with a figure: none unless it is OPEN or PENDING and overdue (its due-by
     *  plus its grace, see overdueFrom, is before the sheet's today) or a correction, else the late percent of
     *  the figure for every 30-day boundary it is overdue past (its due-by plus its grace plus 30, 60, 90 ...
     *  days), compounding: the late percent in force on that boundary date of the figure plus the charges
     *  of the boundaries before it, rounded to two decimals as the claim's date decides (see roundingOf), the
     *  charges added up. It follows the sheet's today until a statement binds the
     *  claim's due, charge included. A waived claim bears none. Whether the claim counts as open is given, so a re-settle can charge a SETTLED
     *  claim as if it were still OPEN. */
    private BigDecimal lateChargeOf(String sheetId, Person person, Claim claim, BigDecimal figure, boolean open,
            LocalDate asOf) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!open || claim.isCorrection() || claim.isWaived()) {
            return none;
        }
        LocalDate from = overdueFrom(sheetId, person, claim, asOf);
        long days = ChronoUnit.DAYS.between(from, todayOr(sheetId, asOf));
        if (days < 30) {
            return none;
        }
        RoundingMode rounding = roundingOf(sheetId, claim, asOf);
        BigDecimal charge = none;
        for (long boundary = 1; boundary <= days / 30; boundary++) {
            BigDecimal pct = settings.latePct(sheetId, from.plusDays(30 * boundary), asOf);
            if (pct.signum() != 0) {
                charge = charge.add(figure.add(charge).multiply(pct).movePointLeft(2).setScale(2, rounding));
            }
        }
        return charge;
    }

    /** The due on a claim at the rate in force on the claim's date for the person's grade in force
     *  on the claim's date, of the rates that apply to the claim (see RateService#rateFor): amount times rate
     *  divided by 100, rounded to two decimals as the claim's date decides (see roundingOf); the floor is
     *  not applied here (see dueOf); null when no rate applies. */
    private BigDecimal liveFigureOf(String sheetId, Person person, Claim claim, LocalDate asOf,
            RateOverride override) {
        BigDecimal amount = claim.getAmount();
        RoundingMode rounding = roundingOf(sheetId, claim, asOf);
        String grade = grades.gradeOn(person.getId(), claim.getDate(), asOf);
        Optional<BigDecimal> inForce = override != null && override.grade().equals(grade) ? Optional.of(override.rate())
                : rates.rateFor(sheetId, grade, claim.getDate(), claim.getId(), asOf).map(Rate::getRate);
        return inForce
                .map(rate -> amount.multiply(rate).movePointLeft(2).setScale(2, rounding))
                .orElse(null);
    }

    /** A grade's rate taken to apply on every date in place of the rates set for it. */
    private record RateOverride(String grade, BigDecimal rate) {
    }

    /** What a rate preview finds: how many claims would show a different due, and the sum of the
     *  differences (new due minus current due). */
    public record RatePreview(int count, BigDecimal diff) {
    }

    /** How the OPEN and PENDING claims of people on a grade (the grade in force on the claim's date)
     *  would change were the rate given the grade's rate on every date: how many would show a different
     *  due and the sum of the differences (a due of n/a counts as 0.00). A claim a statement binds keeps
     *  its figure. Nothing is saved. */
    @Transactional(readOnly = true)
    public RatePreview previewRate(String sheetId, String grade, BigDecimal rate) {
        RateOverride override = new RateOverride(grade, rate);
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, person -> person));
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.getStatus().isOpen()
                    || !grade.equals(grades.gradeOn(person.getId(), claim.getDate(), null))) {
                continue;
            }
            BigDecimal now = dueOf(sheetId, person, claim, null);
            BigDecimal then = dueOf(sheetId, person, claim, null, override);
            BigDecimal was = now == null ? BigDecimal.ZERO : now;
            BigDecimal will = then == null ? BigDecimal.ZERO : then;
            if ((now == null) != (then == null) || was.compareTo(will) != 0) {
                count++;
                diff = diff.add(will.subtract(was));
            }
        }
        return new RatePreview(count, diff.setScale(2, RoundingMode.HALF_UP));
    }

    /** The date a claim's live figures round by: its own, or for a correction the date of the claim
     *  it corrects. */
    public LocalDate roundsBy(String sheetId, Claim claim) {
        if (!claim.isCorrection() || claim.getCorrects() == null) {
            return claim.getDate();
        }
        return claims.findBySheetIdAndPersonIdAndNumber(sheetId, claim.getPersonId(), claim.getCorrects())
                .map(Claim::getDate).orElse(claim.getDate());
    }

    /** How a claim's live figures round: by the sheet's policy date against the date it rounds by. */
    private RoundingMode roundingOf(String sheetId, Claim claim, LocalDate asOf) {
        return settings.roundingOn(sheetId, roundsBy(sheetId, claim), asOf);
    }

    /** Whether a clerk is settling a claim whose due is above the clerk limit in force on the sheet's
     *  today (its person's grade's then when one is set, else the sheet's); a due of n/a is within any
     *  limit, and a steward settles anything. */
    private boolean overClerkLimit(String sheetId, Person person, String user, BigDecimal due) {
        String grade = grades.gradeOn(person.getId(), sheets.today(sheetId));
        BigDecimal limit = settings.clerkLimitFor(sheetId, grade);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        Role role = settings.roleOf(sheetId, user);
        return role == Role.CLERK || role == Role.RESTRICTED;
    }

    /** A claim whose due is above the proof threshold in force on its date (its person's grade's in
     *  force then when one is set, else the sheet's) cannot be settled or approved while it has no
     *  proofs, nor, while the sheet's proof kinds are set, without a proof of each kind listed; a due of
     *  n/a, or no threshold, asks for none. */
    private void refuseUnproved(String sheetId, Person person, Claim claim, BigDecimal due) {
        String grade = grades.gradeOn(person.getId(), claim.getDate());
        BigDecimal threshold = settings.proofThresholdFor(sheetId, grade, claim.getDate());
        if (threshold == null || due == null || due.compareTo(threshold) <= 0) {
            return;
        }
        if (claim.getProofs() == 0) {
            throw new ProofRequiredException(due, threshold);
        }
        for (String kind : settings.proofKinds(sheetId)) {
            if (claim.proofsOf(kind) == 0) {
                throw new ProofRequiredException(due, threshold, kind);
            }
        }
    }

    /** Whether a claim being opened goes to REVIEW: its due is above the review threshold in force on
     *  its date; a due of n/a, or no threshold, does not. */
    private boolean overReviewThreshold(String sheetId, Person person, Claim claim) {
        BigDecimal threshold = settings.reviewThreshold(sheetId, claim.getDate());
        if (threshold == null) {
            return false;
        }
        BigDecimal due = dueOf(sheetId, person, claim);
        return due != null && due.compareTo(threshold) > 0;
    }

    /** A claim whose due is above the note threshold in force on its date cannot be opened while its
     *  note is empty; a due of n/a, or no threshold, asks for none. */
    private void refuseUnnoted(String sheetId, Person person, Claim claim) {
        BigDecimal threshold = settings.noteThreshold(sheetId, claim.getDate());
        if (threshold == null || (claim.getNote() != null && !claim.getNote().isBlank())) {
            return;
        }
        BigDecimal due = dueOf(sheetId, person, claim);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new NoteRequiredException(due, threshold);
        }
    }

    /** A negative amount (a correction) must name the number of a SETTLED claim of the same person,
     *  and may not exceed what remains of it: its amount less the corrections already made against it
     *  (VOID or DELETED ones, and the claim being edited, left out); any other amount names none. */
    private void refuseBadCorrection(String sheetId, Long personId, BigDecimal amount, String corrects,
            Long editedId) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new CorrectionRefusedException("Only a negative amount corrects a claim");
            }
            return;
        }
        if (corrects == null) {
            throw new CorrectionRefusedException("A negative amount must name the claim it corrects");
        }
        boolean settled = claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .map(c -> c.getStatus() == ClaimStatus.SETTLED).orElse(false);
        if (!settled) {
            throw new CorrectionRefusedException("No SETTLED claim " + corrects + " of this person to correct");
        }
        BigDecimal remains = claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects).orElseThrow()
                .getAmount();
        for (Claim made : claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId)) {
            if (corrects.equals(made.getCorrects()) && !made.getId().equals(editedId)
                    && made.getStatus() != ClaimStatus.VOID && made.getStatus() != ClaimStatus.DELETED) {
                remains = remains.add(made.getAmount());
            }
        }
        if (amount.negate().compareTo(remains) > 0) {
            throw new CorrectionRefusedException("A correction may not exceed the " + scaled(remains.max(BigDecimal.ZERO))
                    .toPlainString() + " that remains of claim " + corrects);
        }
    }

    /** Whether a claim on the sheet other than the given one (null: any) already uses the reference,
     *  without regard to case; a null reference is never in use. */
    public boolean refInUse(String sheetId, String ref, Long exceptId) {
        return ref != null && claims.refInUse(sheetId, Claim.keyOf(ref), exceptId);
    }

    /** A reference another claim on the sheet already uses cannot be given again. */
    private void refuseRefInUse(String sheetId, String ref, Long exceptId) {
        if (refInUse(sheetId, ref, exceptId)) {
            throw new RefInUseException("The reference " + ref + " is already used on the sheet");
        }
    }

    /** A claim cannot be opened while its person already has as many OPEN or PENDING claims in the
     *  period its due-by falls in as that period's cap. */
    private void refuseOverCap(String sheetId, Claim claim) {
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(period -> period.getCap() != null && period.contains(claim.getDueBy()))
                .findFirst()
                .ifPresent(period -> {
                    if (claims.countOpenDueBetween(sheetId, claim.getPersonId(), period.getFrom(), period.getTo())
                            >= period.getCap()) {
                        throw new PeriodCapException(period.getName(), period.getCap());
                    }
                });
    }

    /** A claim cannot be opened while its person already has the sheet's maximum of OPEN or PENDING
     *  claims (none while it is blank). */
    private void refuseOverMaxOpen(String sheetId, Claim claim) {
        Integer max = settings.maxOpen(sheetId);
        if (max != null && claims.countOpen(sheetId, claim.getPersonId()) >= max) {
            throw new MaxOpenException(max);
        }
    }

    /** A claim whose due-by falls inside a closed period cannot be added, edited, opened, settled or voided. */
    private void refuseClosed(String sheetId, LocalDate dueBy) {
        if (periods.closedOn(sheetId, dueBy)) {
            throw new PeriodClosedException("A claim due by " + dueBy + " falls in a closed period");
        }
    }

    /** A claim dated after its person's retirement date, or after the from and before the to of a past
     *  retirement of theirs, cannot be added or moved there. */
    private void refuseRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new PersonRetiredException(person.getName(), person.getRetiredFrom(), date);
        }
        for (PersonRetirement past : retirements.findByPersonIdOrderByIdAsc(person.getId())) {
            if (past.covers(date)) {
                throw new PersonRetiredException(person.getName(), past.getFrom(), date);
            }
        }
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
    }

    /** One NOTIFY line for each user watching the claim's person, in the order they started watching. */
    private void notifyWatchers(String sheetId, String user, Person person, Claim claim) {
        for (PersonWatch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + watch.getUser()
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber());
    }

    /** One audit line about a claim; a claim with a number carries it at the end. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, String number) {
        audit.recordOnCommit(line(event, sheetId, user, person, claim, number));
    }

    private static String line(String event, String sheetId, String user, Person person, Claim claim, String number) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** A claim number as typed: trimmed, null when blank. */
    private static String numberOf(String number) {
        return number == null || number.isBlank() ? null : number.trim();
    }

    /** Tags as typed, comma separated: each trimmed and in lower case, blanks and repeats dropped,
     *  sorted. */
    public static SortedSet<String> tagsOf(String tags) {
        SortedSet<String> out = new TreeSet<>();
        if (tags != null) {
            for (String tag : tags.split(",")) {
                if (!tag.isBlank()) {
                    out.add(tag.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** Every tag ever used on the sheet, sorted. */
    public List<String> tagsUsed(String sheetId) {
        return claims.tagsUsed(sheetId);
    }

    /** Keep the claim's tags on the sheet's list of the tags ever used. */
    private void useTags(String sheetId, Claim claim) {
        claim.getTags().forEach(tag -> claims.useTag(sheetId, tag));
    }

    /** An external reference as typed: trimmed, null when blank. */
    public static String refOf(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }

    /** The terms in business days a claim of the person created on a day (the sheet's today then) takes:
     *  the person's own in force that day, else those of the grade they had that day, else the sheet's. */
    private int termsFor(String sheetId, Person person, LocalDate on) {
        Integer own = personTerms.termsOn(person, on);
        return own != null ? own : settings.termsFor(sheetId, grades.gradeOn(person.getId(), on), on);
    }
}
