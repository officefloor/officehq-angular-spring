package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Action;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on each sheet make. A claim left without a date is dated the sheet's entry date,
 * or its today when that is off. A claim's
 * terms are fixed when it is added, from those of its person's grade in force on its date, and its due-by
 * is the terms-th business day after that date, skipping weekends and the holidays on the sheet when it is added; the
 * claim belongs to the period containing that due-by (not its date). Only a DRAFT claim can be edited.
 * No claim whose due-by falls inside a closed period (not only soft-closed) can be added, edited or
 * change status, nor be edited to a date whose due-by, with its terms, would; one added with its due-by inside a soft-closed period is a late entry, and none can be added or edited to a date after its person's retirement date. A claim with a
 * negative amount is a correction: it is refused unless it names, by number, a SETTLED claim of the same
 * person; a claim that is not a correction names none. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final ClaimCommentRepository comments;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final PersonWatchRepository watches;
    private final HolidayService holidays;
    private final Audit audit;
    private final SheetTagRepository tagRepository;
    private final VoidReasonService voidReasons;

    public ClaimService(ClaimRepository claims, ClaimLogRepository logs, PersonRepository people, RollupService rollup, SheetService sheets,
            PeriodRepository periods, SettingsService settings, PersonWatchRepository watches, HolidayService holidays,
            Audit audit, SheetTagRepository tagRepository, VoidReasonService voidReasons,
            ClaimCommentRepository comments) {
        this.comments = comments;
        this.voidReasons = voidReasons;
        this.tagRepository = tagRepository;
        this.holidays = holidays;
        this.watches = watches;
        this.claims = claims;
        this.logs = logs;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** The status logs of the claims, by claim id, each oldest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimLog>> logsOf(List<Claim> of) {
        if (of.isEmpty()) return Map.of();
        return logs.findByClaimIdInOrderByIdAsc(of.stream().map(Claim::getId).toList()).stream()
                .collect(Collectors.groupingBy(ClaimLog::getClaimId));
    }

    /** The comments on the claims, by claim id, each oldest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimComment>> commentsOf(List<Claim> of) {
        if (of.isEmpty()) return Map.of();
        return comments.findByClaimIdInOrderByIdAsc(of.stream().map(Claim::getId).toList()).stream()
                .collect(Collectors.groupingBy(ClaimComment::getClaimId));
    }

    /** Make the claims, settled (or left PENDING) by one bulk settle, the sheet's next settle batch,
     * returning them with its number, in the order given; records BATCH_SETTLED with the batch's number,
     * how many claims it has and the frozen figures of those SETTLED added up. */
    @Transactional
    public List<Claim> settleBatch(String sheetId, String user, List<Claim> settled) {
        int batch = sheets.nextSettleBatch(sheetId);
        List<Claim> batched = settled.stream().map(c -> {
            Claim claim = claims.lockByIdAndSheetId(c.getId(), sheetId).orElseThrow(ClaimNotFoundException::new);
            claim.setSettleBatch(batch);
            return claims.save(claim);
        }).toList();
        BigDecimal total = batched.stream()
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED && c.getSettledDue() != null)
                .map(Claim::getSettledDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        audit.change("BATCH_SETTLED", sheetId, user, "batch=" + batch, "count=" + batched.size(),
                "total=" + total.toPlainString());
        return batched;
    }

    /** Comment on the claim, whatever its status: {@code text} (not blank) is kept with the user and the
     * sheet's today. */
    @Transactional
    public Claim comment(String sheetId, String user, long claimId, String text) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        comments.save(new ClaimComment(claim.getId(), user, sheets.today(sheetId), text));
        record("COMMENT_ADDED", user, person, claim, claim.getNumber(), "text=" + text);
        return claim;
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /** Every tag ever used on the sheet's claims, sorted. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        return tagRepository.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }

    /** Add a claim with the external reference {@code ref} (none when null), refused when another claim
     * on the sheet has it (compared without regard to case), and the tags {@code tags} (see
     * {@link Claim#tagsOf}; none when null). */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate dated = date != null ? date : sheets.entryDate(sheetId);
        int terms = termsOf(sheetId, person, dated);
        LocalDate dueBy = holidays.dueBy(sheetId, dated, terms);
        refuseClosed(sheetId, dueBy);
        person.requireClaimable(dated);
        refuseBadCorrection(sheetId, personId, amount, corrects);
        refuseRefTaken(sheetId, null, ref);
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note), corrects);
        claim.setRef(ref);
        claim.setTags(tags);
        claim.fixTerms(dated, terms);
        claim.setDueBy(dueBy);
        claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        claim.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        claim = claims.saveAndFlush(claim);
        log(user, person, claim);
        keepTags(claim);
        List<String> extra = new ArrayList<>();
        if (ref != null) extra.add("ref=" + ref);
        if (tags != null) extra.add("tags=" + tags);
        record("CLAIM_ADDED", user, person, claim, claim.getNumber(), extra.toArray(String[]::new));
        if (claim.isLateEntry()) record("LATE_ENTRY", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.isLockedFor(user, sheets.today(sheetId))) throw new ClaimLockedException();
        LocalDate dated = date != null ? date : sheets.entryDate(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        refuseClosed(sheetId, holidays.dueBy(sheetId, dated, claim.getTerms()));
        if (claim.getStatus() != ClaimStatus.DRAFT) throw new ClaimNotEditableException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        person.requireClaimable(dated);
        refuseBadCorrection(sheetId, person.getId(), amount, corrects);
        refuseRefTaken(sheetId, claim.getId(), ref);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(dated);
        claim.setNote(noteOf(note));
        claim.setRef(ref);
        claim.setTags(tags);
        claim.setEditedBy(user);
        keepTags(claim);
        claim.unlock();
        claims.flush();
        record("CLAIM_EDITED", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Open the claim's editor for {@code user}, locking the claim for everyone else until it is saved
     * (see {@link #update}) or cancelled (see {@link #unlock}), or the sheet's today changes. Refused,
     * taking no lock, when the claim cannot be edited (not a DRAFT, or dated in a closed period) or
     * another user holds its lock. */
    @Transactional
    public Claim lock(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate today = sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) throw new ClaimNotEditableException();
        if (claim.isLockedFor(user, today)) throw new ClaimLockedException();
        claim.lock(user, today);
        return claim;
    }

    /** Close {@code user}'s editor of the claim without saving, releasing the lock it holds; a lock
     * another user holds is left alone. */
    @Transactional
    public Claim unlock(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (user.equals(claim.getLockedBy())) claim.unlock();
        return claim;
    }

    /** Move the claim to {@code next}; refused when the user's role may not open, settle or void it (see
     * {@link SettingsService#allowed}), and when its current status does not allow it (a PENDING
     * claim can only be voided here: it is approved or rejected instead). Settling it fixes what is due
     * on it then (the figure it is bound to, when a statement bound it, otherwise its person's rate then)
     * together with its late charge then (none on a correction); the user who opened it or last edited it
     * cannot settle it,
     * and a clerk's settle of it when that due is above the sheet's clerk limit (a due that is n/a is
     * within any limit) leaves it PENDING instead, fixing nothing, until a steward approves or rejects
     * it. A claim deferred on the sheet's today cannot be settled, nor one without proofs whose due is
     * above the proof threshold in force on its date (refused before the clerk limit is considered). A
     * SETTLED claim can only be voided by a steward, and only while the sheet's today is at most 7 days
     * after its settlement (its status log's SETTLED date); its frozen figure then leaves every total and
     * is recorded as reversed. A voided claim keeps its number, if it had one. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        return changeStatus(sheetId, user, claimId, next, null);
    }

    /** Move the claim to {@code next} as {@link #changeStatus(String, String, long, ClaimStatus)} does;
     * a void is given {@code voidReason}, which while the sheet's list of void reasons is not empty must
     * be one on it (see {@link VoidReasonService#refuseUnlisted}), and is kept on the claim. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next, String voidReason) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        Action action = switch (next) {
            case OPEN -> Action.OPEN;
            case SETTLED -> Action.SETTLE;
            case VOID -> Action.VOID;
            default -> null;
        };
        if (action != null && !settings.allowed(sheetId, user, action)) throw new ClaimNotStewardException();
        refuseClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(next) || (claim.getStatus() == ClaimStatus.PENDING && next != ClaimStatus.VOID)) {
            throw new ClaimStatusChangeException();
        }
        boolean reversing = claim.getStatus() == ClaimStatus.SETTLED;
        if (reversing) refuseLateVoid(sheetId, user, claim);
        String reason = next == ClaimStatus.VOID ? voidReasons.refuseUnlisted(sheetId, voidReason) : null;
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) throw new ClaimStatusChangeException();
        if (next == ClaimStatus.SETTLED && claim.isOpenedOrEditedBy(user)) throw new ClaimSelfSettleException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (next == ClaimStatus.OPEN) refuseNoteless(claim, rollup.shownOf(claim, person));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String number = claim.getNumber();
        if (next == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDueBy());
            claim.setOpenedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isStated() ? claim.getStatedDue() : rollup.dueOf(claim, person);
            BigDecimal due = rollup.liveDueOf(claim, figure);
            refuseUnproved(claim, person, due);
            if (overLimit(sheetId, user, due)) next = ClaimStatus.PENDING;
            else claim.setSettledDue(due);
        }
        claim.setStatus(next);
        claim.setNumber(number);
        if (next == ClaimStatus.VOID) claim.setVoidReason(reason);
        claims.flush();
        log(user, person, claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        if (next == ClaimStatus.SETTLED) {
            record(event, user, person, claim, number, "due=" + dueText(claim.getSettledDue()));
            notifyWatchers(user, person, claim);
        } else {
            List<String> extra = new ArrayList<>();
            if (reversing) extra.add("reversed=" + dueText(claim.getSettledDue()));
            if (reason != null) extra.add("reason=" + reason);
            record(event, user, person, claim, number, extra.toArray(String[]::new));
        }
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Defer an OPEN claim until {@code until}, a date after the sheet's today: until the sheet's today
     * reaches it the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     * statement. Refused unless the claim is OPEN, when {@code until} is not after the sheet's today, and
     * when the claim is dated in a closed period. */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) throw new ClaimStatusChangeException();
        if (!until.isAfter(sheets.today(sheetId))) throw new ClaimDeferException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        record("CLAIM_DEFERRED", user, person, claim, claim.getNumber(), "until=" + until);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Remind an OPEN or PENDING claim: a reminder is written and the claim is reminded on the sheet's
     * today. Refused unless the claim is OPEN or PENDING, when it was already reminded on the sheet's
     * today, and when its due-by is in a closed period. */
    @Transactional
    public Claim remind(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusChangeException();
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) throw new ClaimRemindedException();
        refuseClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setRemindedOn(today);
        claims.flush();
        record("REMIND", user, person, claim, claim.getNumber());
        return claim;
    }

    /** A steward's re-date of an OPEN or PENDING claim to {@code date}. Its due-by stays as it was fixed,
     * so its period and number stay too, and a figure a statement bound it to stays bound. Refused when the
     * user is not a steward, unless the claim is OPEN or PENDING, when its due-by is in a closed period,
     * and when the new date is after its person's retirement date. */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusChangeException();
        }
        refuseClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        person.requireClaimable(date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        claims.flush();
        record("CLAIM_REDATED", user, person, claim, claim.getNumber(), "old=" + old, "new=" + date);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Split {@code amount} (more than 0.00 and less than the claim's amount) off an OPEN claim: the
     * claim keeps the rest and loses any figure a statement bound it to; a new OPEN claim of the split-off
     * amount, with the same person, date and note, is given the next number, is opened by the user, has
     * its due-by fixed now from the terms in force on its date and names the claim's number as the one
     * it was split from; it is a late entry when that due-by falls in a soft-closed period. Refused unless
     * the claim is OPEN and not deferred on the sheet's today, and when its due-by or the new claim's falls
     * in a closed period. Both claims are returned, the original first. */
    @Transactional
    public List<Claim> split(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN || claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimStatusChangeException();
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) throw new ClaimSplitException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unbind();
        Claim split = new Claim(sheetId, person.getId(), amount, claim.getDate(), claim.getNote(), null);
        int terms = termsOf(sheetId, person, claim.getDate());
        split.fixTerms(claim.getDate(), terms);
        split.setDueBy(holidays.dueBy(sheetId, claim.getDate(), terms));
        refuseClosed(sheetId, split.getDueBy());
        split.setLateEntry(periods.softClosedOn(sheetId, split.getDueBy()));
        split.setListed(sheets.nextInSequence(sheetId, "listed"));
        split.setStatus(ClaimStatus.OPEN);
        split.setNumber(nextNumber(sheetId, split.getDueBy()));
        split.setOpenedBy(user);
        split.setSplitFrom(claim.getNumber());
        split.setTags(claim.getTags().isEmpty() ? null : String.join(",", claim.getTags()));
        split = claims.saveAndFlush(split);
        log(user, person, split);
        record("CLAIM_SPLIT", user, person, claim, claim.getNumber(), "into=" + split.getNumber());
        record("CLAIM_OPENED", user, person, split, split.getNumber());
        rollup.recordSince(sheetId, user, before);
        return List.of(claim, split);
    }

    /** A steward's approval of a PENDING claim: it becomes SETTLED, fixing what is due on it then (its
     * live figure, which no statement binds while it is PENDING, together with its late charge then).
     * Refused unless the claim is PENDING, when the user is not a steward or opened or last edited the claim, and when it
     * has no proofs and that due is above the proof threshold in force on its date. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = lockPending(sheetId, user, claimId);
        if (claim.isOpenedOrEditedBy(user)) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal figure = rollup.dueOf(claim, person);
        BigDecimal due = rollup.liveDueOf(claim, figure);
        refuseUnproved(claim, person, due);
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        claims.flush();
        log(user, person, claim);
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(), "due=" + dueText(due));
        notifyWatchers(user, person, claim);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Raise the claim's proofs count by one. Refused when the claim is SETTLED or VOID, and when it is
     * dated in a closed period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ClaimStatusChangeException();
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.addProof();
        claims.flush();
        record("CLAIM_PROOF_ADDED", user, person, claim, claim.getNumber(), "proofs=" + claim.getProofs());
        return claim;
    }

    /** A steward's rejection of a PENDING claim, for {@code reason}: it goes back to OPEN, bound again
     * by its person's last statement if that statement included it. Refused unless the claim is
     * PENDING, and when the user is not a steward. */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = lockPending(sheetId, user, claimId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claims.flush();
        log(user, person, claim);
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), "reason=" + reason);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A move, by a role the permission matrix allows to reassign (a steward by default), of a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its
     * date but, unless a draft (which has no number), is given the next number in the sequence it is
     * numbered in (as when opened now), its old number never used again; it loses
     * any figure a statement bound it to (it is live again until its new person's next statement), and is listed as its new person's newest claim. Refused when the user's
     * role may not reassign, when the claim is SETTLED or VOID, is a correction (it corrects a claim of its
     * own person) or already belongs to the person, when it is dated in a closed period, and when it
     * is dated after the new person's retirement date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId) {
        return move(sheetId, user, claimId, personId, false);
    }

    /** A move of one of several claims moved together: by the rules of {@link #reassign}, except that a
     * claim that already belongs to the person goes through (it is listed as the person's newest claim,
     * and recorded). */
    @Transactional
    public Claim reassignOfBulk(String sheetId, String user, long claimId, long personId) {
        return move(sheetId, user, claimId, personId, true);
    }

    private Claim move(String sheetId, String user, long claimId, long personId, boolean samePerson) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.REASSIGN)) throw new ClaimNotStewardException();
        refuseClosed(sheetId, claim.getDueBy());
        ClaimStatus status = claim.getStatus();
        if ((status != ClaimStatus.DRAFT && status != ClaimStatus.OPEN && status != ClaimStatus.PENDING)
                || claim.isCorrection() || (!samePerson && claim.getPersonId() == personId)) {
            throw new ClaimReassignException();
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        to.requireClaimable(claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        if (claim.getNumber() != null) claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
        claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        claims.flush();
        record("CLAIM_REASSIGNED", user, to, claim, claim.getNumber(), "from=" + from.getName(), "to=" + to.getName());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's re-settle of a SETTLED claim: it is fixed again at what would be due on it now were
     * it OPEN and bound by no statement (its person's rate and grade, the sheet's floor and its late
     * charge, all as they are now). Refused unless the claim is SETTLED, when the user is not a
     * steward, and when it is dated in a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.SETTLED) throw new ClaimStatusChangeException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal old = claim.getSettledDue();
        BigDecimal due = rollup.openShownOf(claim, person);
        claim.setSettledDue(due);
        claims.flush();
        log(user, person, claim);
        record("CLAIM_RESETTLED", user, person, claim, claim.getNumber(), "old=" + dueText(old), "new=" + dueText(due));
        notifyWatchers(user, person, claim);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Refuse a void of the SETTLED claim unless the user is a steward and the sheet's today is at most
     * 7 days after the date its status log first shows it SETTLED. */
    private void refuseLateVoid(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        LocalDate settled = logs.findByClaimIdInOrderByIdAsc(List.of(claim.getId())).stream()
                .filter(entry -> entry.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getDate)
                .findFirst()
                .orElseThrow(ClaimStatusChangeException::new);
        if (sheets.today(sheetId).isAfter(settled.plusDays(7))) throw new ClaimVoidLateException();
    }

    /** The PENDING claim, locked, for a steward to approve or reject. */
    private Claim lockPending(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) throw new ClaimStatusChangeException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        return claim;
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The figure the claim shows and totals now, without its late charge or floor; null when it has no figure
     * (see {@link RollupService#figureOf}). */
    public BigDecimal figureOf(Claim claim, Person person) {
        return rollup.figureOf(claim, person);
    }

    /** What is due on the claim as it shows and totals now, its late charge included; null when it has
     * no figure (see {@link RollupService#shownOf}). */
    public BigDecimal shownOf(Claim claim, Person person) {
        return rollup.shownOf(claim, person);
    }

    /** Keep each of the claim's tags among those ever used on its sheet. */
    public void keepTags(Claim claim) {
        for (String tag : claim.getTags()) {
            SheetTag.Key key = new SheetTag.Key(claim.getSheetId(), tag);
            if (!tagRepository.existsById(key)) tagRepository.save(new SheetTag(claim.getSheetId(), tag));
        }
    }

    /** Write the status the claim has now to its log, with the sheet's today and what is due on it as
     * it shows now. */
    private void log(String user, Person person, Claim claim) {
        logs.save(new ClaimLog(claim.getId(), claim.getStatus(), user, sheets.today(claim.getSheetId()),
                rollup.shownOf(claim, person)));
    }

    /** Record a change to the claim; {@code number}, the number it has (or had), follows when there is one,
     * then any {@code extra} fields. */
    private void record(String event, String user, Person person, Claim claim, String number, String... extra) {
        List<String> fields = new ArrayList<>(List.of("person=" + person.getName(),
                "amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "date=" + claim.getDate()));
        if (number != null) fields.add("number=" + number);
        fields.addAll(List.of(extra));
        audit.change(event, claim.getSheetId(), user, fields.toArray(String[]::new));
    }

    /** Notify each user watching the person, in the order they started watching, that the claim was
     * settled, approved or re-settled. */
    private void notifyWatchers(String user, Person person, Claim claim) {
        for (PersonWatch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.change("NOTIFY", claim.getSheetId(), user, "user=" + watch.getUser(), "person=" + person.getName(),
                    "number=" + claim.getNumber());
        }
    }

    /** The number a claim due by {@code date} is given when opened now: within the period containing
     * that date ({@code <period name>-<n>}, or {@code X-<n>} when none does) while the sheet numbers by
     * period, otherwise the next sheet-wide number. */
    public String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.numbersByPeriod(sheetId)) return String.valueOf(sheets.nextClaimNumber(sheetId));
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !date.isBefore(p.getFrom()) && !date.isAfter(p.getTo()))
                .findFirst()
                .map(p -> p.getName() + "-" + sheets.nextInSequence(sheetId, "period:" + p.getId()))
                .orElseGet(() -> "X-" + sheets.nextInSequence(sheetId, "none"));
    }

    /** Whether a settle by {@code user} of a claim whose due is {@code due} needs a steward's approval:
     * the user is a clerk and the due is above the sheet's clerk limit. */
    private boolean overLimit(String sheetId, String user, BigDecimal due) {
        if (due == null || settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due.compareTo(limit) > 0;
    }

    /** Refuse a settle or approval of the claim, whose due is {@code due}, while it has no proofs and that
     * due is above the proof threshold in force on its date: its person's grade's own then, else the sheet's
     * (a due that is n/a is within any threshold). */
    private void refuseUnproved(Claim claim, Person person, BigDecimal due) {
        if (due == null || claim.getProofs() > 0) return;
        BigDecimal threshold = settings.proofThresholdFor(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) throw new ClaimProofRequiredException();
    }

    /** Refuse an open of the claim, whose due is {@code due}, while its note is empty and that due is
     * above the note threshold in force on its date (a due that is n/a is within any threshold). */
    private void refuseNoteless(Claim claim, BigDecimal due) {
        if (due == null || !noteOf(claim.getNote()).isEmpty()) return;
        BigDecimal threshold = settings.noteThresholdOn(claim.getSheetId(), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) throw new ClaimNoteRequiredException();
    }

    /** Refuse a correction (a negative {@code amount}) that does not name, by number, a SETTLED claim of
     * the person, and a claim that names one without being a correction. */
    private void refuseBadCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) throw new ClaimCorrectionException();
            return;
        }
        if (corrects == null) throw new ClaimCorrectionException();
        claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(corrected -> corrected.getStatus() == ClaimStatus.SETTLED)
                .orElseThrow(ClaimCorrectionException::new);
    }

    /** Refuse the external reference {@code ref} when a claim on the sheet other than {@code claimId} has
     * it, compared without regard to case. */
    private void refuseRefTaken(String sheetId, Long claimId, String ref) {
        if (ref == null) return;
        claims.findBySheetIdAndRefKey(sheetId, Claim.keyOf(ref))
                .filter(other -> !other.getId().equals(claimId))
                .ifPresent(other -> {
                    throw new ClaimRefTakenException();
                });
    }

    /** Refuse a change to a claim due by {@code date} when a closed period of the sheet contains it. */
    private void refuseClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new ClaimPeriodClosedException();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }

    /** The terms a claim of the person dated {@code date} is due by: the person's own terms, else those
     * of their grade in force on that date, else the sheet's then. */
    private int termsOf(String sheetId, Person person, LocalDate date) {
        return person.getTerms() != null ? person.getTerms() : settings.termsFor(sheetId, person.gradeOn(date), date);
    }
}
