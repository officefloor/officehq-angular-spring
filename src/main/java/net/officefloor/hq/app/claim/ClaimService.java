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
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.setting.Permission;
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
    private final PersonRepository people;
    private final RateService rates;
    private final PersonGradeService grades;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final PersonWatchRepository watches;
    private final HolidayService holidays;
    private final VoidReasonService voidReasons;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimLogRepository logs, PersonRepository people, RateService rates,
            PersonGradeService grades, SheetService sheets, PeriodRepository periods, SettingService settings, PersonWatchRepository watches,
            HolidayService holidays, VoidReasonService voidReasons, ClaimCommentRepository comments, Audit audit) {
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
     *  business day after its date that is the person's own terms, else the terms in force on that date for the
     *  person's grade then, counted skipping weekends and the sheet's holidays now. A negative amount is a
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
        int terms = termsFor(sheetId, person, on);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        refuseClosed(sheetId, dueBy);
        refuseRetired(person, on);
        refuseBadCorrection(sheetId, personId, amount, corrects);
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
        refuseBadCorrection(sheetId, personId, amount, corrects);
        ref = refOf(ref);
        refuseRefInUse(sheetId, ref, claim.getId());
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
        Person person = person(sheetId, personId);
        // Lock the sheet first, so a claim opened at the same moment elsewhere waits and the claim
        // read below is its latest state.
        if (to == ClaimStatus.OPEN) {
            sheets.lock(sheetId);
        }
        Claim claim = claim(sheetId, personId, id);
        settings.refuseUnless(sheetId, user, switch (to) {
            case OPEN -> Permission.OPEN;
            case SETTLED -> Permission.SETTLE;
            case VOID -> Permission.VOID;
            case DRAFT, PENDING -> throw new StatusChangeRefusedException(claim.getStatus(), to);
        });
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
        if (to == ClaimStatus.SETTLED && claim.openedOrEditedBy(user)) {
            throw new OwnClaimSettleRefusedException(user);
        }
        if (to == ClaimStatus.SETTLED && claim.deferredOn(sheets.today(sheetId))) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        // Taken while the claim is still OPEN, so a settled due freezes in the late charge it has
        // gathered by then.
        BigDecimal due = to == ClaimStatus.SETTLED ? dueOf(sheetId, person, claim) : null;
        if (to == ClaimStatus.SETTLED) {
            refuseUnproved(sheetId, person, claim, due);
        }
        if (to == ClaimStatus.OPEN) {
            refuseUnnoted(sheetId, person, claim);
        }
        if (to == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, due)) {
            to = ClaimStatus.PENDING;
        }
        claim.setStatus(to);
        if (to == ClaimStatus.SETTLED || to == ClaimStatus.PENDING) {
            // Settled one at a time until a bulk settle marks it with its batch.
            claim.setBatch(null);
        }
        if (to == ClaimStatus.OPEN) {
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        } else if (to == ClaimStatus.VOID) {
            claim.setVoidReason(reason);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException("A claim never returns to DRAFT");
        };
        String line = line(event, sheetId, user, person, saved, saved.getNumber());
        if (to == ClaimStatus.SETTLED) {
            line += " due=" + settledDueText(saved);
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

    /** Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it
     *  the claim counts in no total, is not overdue, cannot be settled and no statement binds it. A
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
     *  note, the next number, opened by the user and due by its date plus the terms in force on it,
     *  is listed under the person naming the claim it was split from (a late entry when its due-by falls
     *  in a soft-closed period). A claim that is not OPEN, one
     *  deferred, one dated in a closed period, an amount not above 0.00 and below the claim's, or a
     *  user whose role may not open a claim, is refused. */
    @Transactional
    public Split split(String sheetId, String user, Long personId, Long id, BigDecimal amount) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, as opening does: the new claim takes the next number.
        sheets.lock(sheetId);
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
        int terms = termsFor(sheetId, person, on);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        Claim split = Claim.splitOff(kept, part, dueBy, terms, nextNumber(sheetId, dueBy), user);
        if (periods.softClosedOn(sheetId, dueBy)) {
            split.markLateEntry();
        }
        Claim added = claims.saveAndFlush(split);
        log(sheetId, user, person, added);
        audit.recordOnCommit(line("CLAIM_SPLIT", sheetId, user, person, kept, kept.getNumber())
                + " into=" + added.getNumber());
        record("CLAIM_OPENED", sheetId, user, person, added);
        return new Split(kept, added);
    }

    /** A steward approves a PENDING claim: it is SETTLED, its due frozen at this moment. Anyone else,
     *  the user who opened or last edited it, or a claim that is not PENDING, is refused. */
    @Transactional
    public Claim approve(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = pending(sheetId, user, personId, id, ClaimStatus.SETTLED);
        if (claim.openedOrEditedBy(user)) {
            throw new OwnClaimSettleRefusedException(user);
        }
        BigDecimal due = dueOf(sheetId, person, claim);
        refuseUnproved(sheetId, person, claim, due);
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber())
                + " due=" + settledDueText(saved));
        notifyWatchers(sheetId, user, person, saved);
        return saved;
    }

    /** Remind an OPEN or PENDING claim: REMIND is written and the claim's reminded date is the sheet's
     *  today. A claim in any other status, or one already reminded on the sheet's today, is refused. */
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
        claim.setRemindedOn(today);
        Claim saved = claims.saveAndFlush(claim);
        record("REMIND", sheetId, user, person, saved);
        return saved;
    }

    /** Add a proof to a claim: its proofs count rises by one. A SETTLED or VOID claim, or one dated in
     *  a closed period, is refused. */
    @Transactional
    public Claim addProof(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ProofRefusedException(claim.getStatus());
        }
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(line("CLAIM_PROOF_ADDED", sheetId, user, person, saved, saved.getNumber())
                + " proofs=" + saved.getProofs());
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
        Claim claim = pending(sheetId, user, personId, id, ClaimStatus.OPEN);
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
        claim.setSettledDue(figure == null ? null
                : floored(sheetId, claim, figure.add(lateChargeOf(sheetId, person, claim, figure, true, null)), null));
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

    /** The PENDING claim a steward approves or rejects; refused when it is not PENDING, dated in a
     *  closed period, or the user is not a steward. */
    private Claim pending(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
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

    /** The number a claim opened now takes: under period numbering (set, and the sheet's today on or
     *  after it) the next within the period its due-by falls in, <period>-<n>, or X-<n> when it falls
     *  in none; otherwise the sheet's next sheet-wide number. Taken with the sheet locked. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!periodNumbering(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
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

    /** The figure a claim shows: the due fixed when it was settled, else (unless it is PENDING, which
     *  no statement binds) the figure its person's last statement bound it to, else its due at the
     *  person's grade rate; null when it has none. */
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
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue()
                : liveFigureOf(sheetId, person, claim, asOf, override);
    }

    /** The due a claim shows: a settled claim's frozen due; otherwise the greater of the sheet's floor in
     *  force on its date (none for a correction) and its figure plus its late charge (none while it is
     *  deferred: it is not overdue then); null when it has no figure. */
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
        BigDecimal figure = figureOf(sheetId, person, claim, asOf, override);
        boolean open = claim.getStatus().isOpen() && !claim.deferredOn(todayOr(sheetId, asOf));
        return figure == null ? null
                : floored(sheetId, claim, figure.add(lateChargeOf(sheetId, person, claim, figure, open, asOf)), asOf);
    }

    /** The day that stands for the sheet's today: the given one, else the sheet's today. */
    private LocalDate todayOr(String sheetId, LocalDate asOf) {
        return asOf != null ? asOf : sheets.today(sheetId);
    }

    /** A due raised to the sheet's floor in force on the claim's date, unless the claim is a correction. */
    private BigDecimal floored(String sheetId, Claim claim, BigDecimal due, LocalDate asOf) {
        return claim.isCorrection() ? due : due.max(settings.floor(sheetId, claim.getDate(), asOf));
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
     *  charges added up. Never bound by a statement: it follows the
     *  sheet's today. Whether the claim counts as open is given, so a re-settle can charge a SETTLED
     *  claim as if it were still OPEN. */
    private BigDecimal lateChargeOf(String sheetId, Person person, Claim claim, BigDecimal figure, boolean open,
            LocalDate asOf) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!open || claim.isCorrection()) {
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
     *  on the claim's date: amount times rate
     *  divided by 100, rounded to two decimals as the claim's date decides (see roundingOf); the floor is
     *  not applied here (see dueOf); null when no rate applies. */
    private BigDecimal liveFigureOf(String sheetId, Person person, Claim claim, LocalDate asOf,
            RateOverride override) {
        BigDecimal amount = claim.getAmount();
        RoundingMode rounding = roundingOf(sheetId, claim, asOf);
        String grade = grades.gradeOn(person.getId(), claim.getDate(), asOf);
        Optional<BigDecimal> inForce = override != null && override.grade().equals(grade) ? Optional.of(override.rate())
                : rates.rateOn(sheetId, grade, claim.getDate(), asOf).map(Rate::getRate);
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

    /** Whether a clerk is settling a claim whose due is above the sheet's clerk limit; a due of n/a
     *  is within any limit, and a steward settles anything. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = settings.clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return settings.roleOf(sheetId, user) == Role.CLERK;
    }

    /** A claim whose due is above the proof threshold in force on its date (its person's grade's in
     *  force then when one is set, else the sheet's) cannot be settled or approved while it has no
     *  proofs; a due of n/a, or no threshold, asks for none. */
    private void refuseUnproved(String sheetId, Person person, Claim claim, BigDecimal due) {
        String grade = grades.gradeOn(person.getId(), claim.getDate());
        BigDecimal threshold = settings.proofThresholdFor(sheetId, grade, claim.getDate());
        if (claim.getProofs() == 0 && threshold != null && due != null && due.compareTo(threshold) > 0) {
            throw new ProofRequiredException(due, threshold);
        }
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

    /** A negative amount (a correction) must name the number of a SETTLED claim of the same person;
     *  any other amount names none. */
    private void refuseBadCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
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

    /** A claim whose due-by falls inside a closed period cannot be added, edited, opened, settled or voided. */
    private void refuseClosed(String sheetId, LocalDate dueBy) {
        if (periods.closedOn(sheetId, dueBy)) {
            throw new PeriodClosedException("A claim due by " + dueBy + " falls in a closed period");
        }
    }

    /** A claim dated after its person's retirement date cannot be added or moved there. */
    private static void refuseRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new PersonRetiredException(person.getName(), person.getRetiredFrom(), date);
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

    /** The terms in business days a claim of the person dated on a date takes: the person's own, else their
     *  grade's in force then, else the sheet's. */
    private int termsFor(String sheetId, Person person, LocalDate on) {
        return person.getTerms() != null ? person.getTerms()
                : settings.termsFor(sheetId, grades.gradeOn(person.getId(), on), on);
    }
}
