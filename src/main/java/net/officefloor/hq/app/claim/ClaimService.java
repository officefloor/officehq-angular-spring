package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people's claims on a sheet. A claim with no date is dated the sheet's today. A claim dated in a closed
 * period cannot be added, edited, deferred or change status. Only a DRAFT claim can be edited; a PENDING claim, waiting for a
 * steward to approve or reject it, is otherwise handled like an OPEN one. No claim
 * can be dated after the retirement date of its person. A claim whose due is above the proof threshold on its date
 * cannot be settled or approved without a proof. A claim with a negative amount is a correction: it must name,
 * by number, a SETTLED claim of the same person that it corrects, and its due has no floor and no late charge.
 */
@Service
public class ClaimService {

    /** The days after its settlement within which a steward may still void a SETTLED claim. */
    private static final int SETTLED_VOID_DAYS = 7;

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;
    private final SettingService settings;
    private final ClaimLogRepository logs;
    private final WatchService watches;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles, SettingService settings,
            ClaimLogRepository logs, WatchService watches) {
        this.logs = logs;
        this.watches = watches;
        this.periods = periods;
        this.roles = roles;
        this.settings = settings;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** The status logs of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<ClaimLog>> logs(String sheetId) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(ClaimLog::getClaimId, Collectors.toList()));
    }

    /** The claim's status log, oldest first. */
    public List<ClaimLog> logOf(Claim claim) {
        return logs.findByClaimIdOrderByIdAsc(claim.getId());
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        LocalDate dated = dateOr(sheetId, date);
        periods.requireOpen(sheetId, dated);
        person.requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, personId, amount, corrects);
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note));
        claim.setCorrects(corrects);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        // Due by its date plus its person's terms on that date, fixed from now on.
        claim.setDueBy(dated.plusDays(termsFor(sheetId, person, dated)));
        claim.setListed(sheets.nextListed(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_ADDED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        // Only a DRAFT can be edited: an OPEN claim is changed by voiding it and adding a new one.
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        // Not while another user has its editor open.
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        LocalDate dated = dateOr(sheetId, date);
        // Neither out of nor into a closed period.
        periods.requireOpen(sheetId, claim.getDate(), dated);
        people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()))
                .requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, claim.getPersonId(), amount, corrects);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(dated);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        claim.setNote(noteOf(note));
        // Saving the editor releases its lock.
        claim.setLockedBy(null);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, saved.getPersonId()));
        audit.recordOnCommit(record("CLAIM_EDITED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Open the claim's editor for the user, locking the claim against other users' edits until it is saved or
     * cancelled, or the sheet's today changes. Refused, taking no lock, for a claim that cannot be edited: not a DRAFT,
     * dated in a closed period, or locked by another user. The user's own lock is taken again.
     */
    @Transactional
    public Claim lock(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        periods.requireOpen(sheetId, claim.getDate());
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel the user's editor of the claim, releasing its lock; another user's lock is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            return claims.saveAndFlush(claim);
        }
        return claim;
    }

    /**
     * Move a claim to another status, if its current one allows it; refused otherwise. A clerk settling a claim whose
     * due is above the clerk limit sends it to PENDING instead, for a steward to approve or reject.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        boolean voidsSettled = status == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (!voidsSettled && (!claim.getStatus().canMoveTo(status) || status == ClaimStatus.PENDING)) {
            throw new ClaimStatusException(id, claim.getStatus(), status);
        }
        // The sheet's permission matrix decides which roles may open, settle or void.
        String action = switch (status) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            case DRAFT, PENDING -> throw new ClaimStatusException(id, claim.getStatus(), status);
        };
        if (!roles.may(sheetId, user, action)) {
            throw new ClaimActionForbiddenException(id, user, action);
        }
        if (voidsSettled) {
            requireSettledVoidable(sheetId, user, claim);
        }
        periods.requireOpen(sheetId, claim.getDate());
        // A deferred claim cannot be settled until the sheet's today reaches the date it is deferred until.
        if (status == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimDeferException(id, "it is deferred until " + claim.getDeferredUntil());
        }
        // Four eyes: whoever opened a claim cannot also settle it.
        if (status == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettledByOpenerException(id, user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        if (status == ClaimStatus.SETTLED) {
            requireProof(sheetId, person, claim);
        }
        // A clerk settles only a claim whose due (the one its statement bound it to, else worked out from the rate)
        // is at or below the clerk limit; one above it goes to PENDING. A due of n/a is within any limit.
        if (status == ClaimStatus.SETTLED && roles.roleOf(sheetId, user) == Role.CLERK) {
            BigDecimal limit = settings.get(sheetId).getClerkLimit();
            BigDecimal due = liveDue(sheetId, claim, figureOf(sheetId, person, claim));
            if (limit != null && due != null && due.compareTo(limit) > 0) {
                status = ClaimStatus.PENDING;
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = status == ClaimStatus.SETTLED ? settle(sheetId, person, claim)
                : voidsSettled ? " reversed=" + shownDue(sheetId, person, claim) : "";
        claim.setStatus(status);
        // A voided claim keeps its number (never reused), and the CLAIM_VOIDED record carries it.
        String number = claim.getNumber();
        if (status == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDate());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (status) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, user, person, saved) + numberOf(number) + due);
        if (status == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward other than its opener approves a PENDING claim: it becomes SETTLED, its due frozen at that moment from
     * its live figure.
     */
    @Transactional
    public Claim approve(String sheetId, String user, Long id) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.SETTLED);
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimApprovalForbiddenException(id, user, "whoever opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        requireProof(sheetId, person, claim);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = settle(sheetId, person, claim);
        claim.setStatus(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_APPROVED", user, person, saved) + numberOf(saved.getNumber()) + due);
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward rejects a PENDING claim with a reason: it goes back to OPEN, bound again by its person's last statement
     * if that statement included it.
     */
    @Transactional
    public Claim reject(String sheetId, String user, Long id, String reason) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.OPEN);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_REJECTED", user, person, saved) + numberOf(saved.getNumber())
                + " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A user whose role may reassign moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and date,
     * loses any figure a statement bound it to (live again until its new person's next statement), and is listed as
     * its new person's newest claim. Refused for a SETTLED or VOID claim, its own person, a closed period, a new person
     * retired before its date, or a user whose role may not reassign.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!roles.may(sheetId, user, "reassign")) {
            throw new ClaimReassignForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ClaimReassignException(id, "it is " + claim.getStatus());
        }
        if (claim.getPersonId().equals(toPersonId)) {
            throw new ClaimReassignException(id, "it is already that person's");
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, toPersonId));
        to.requireClaimable(claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.setStated(false, null);
        claim.setListed(sheets.nextListed(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REASSIGNED", user, to, saved) + numberOf(saved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the claim counts in
     * no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for a claim not OPEN, a
     * date not after today, or a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, Long id, LocalDate until) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimDeferException(id, "it is " + claim.getStatus() + ", only an OPEN claim can be deferred");
        }
        LocalDate today = sheets.today(sheetId);
        if (until == null || !until.isAfter(today)) {
            throw new ClaimDeferException(id, "it can only be deferred to a date after " + today);
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DEFERRED", user, person, saved) + numberOf(saved.getNumber())
                + " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-settles a SETTLED claim: its due is frozen again at the figure it would have today as an OPEN claim
     * bound by no statement (today's rates and grades with its late charge as of today, raised to the floor). Refused for any other
     * status, a closed period, or a user not a steward.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimResettleForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ClaimStatusException(id, claim.getStatus(), ClaimStatus.SETTLED);
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String old = shownDue(sheetId, person, claim);
        BigDecimal figure = dueOf(sheetId, person, claim);
        Sheet sheet = settings.get(sheetId);
        claim.setSettledDue(claim.isCorrection() ? figure
                : sheet.floored(sheet.withLateCharge(figure, true, claim.getDueBy(), claim.getDate()), claim.getDate()));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_RESETTLED", user, person, saved) + numberOf(saved.getNumber())
                + " old=" + old + " new=" + shownDue(sheetId, person, saved));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure its statement bound it
     * to stays bound; its period follows the new date, and while the sheet numbers by period a claim moved into another
     * period takes the next number of that period's sequence (its old one is never used again). Refused for any other
     * status, a date after its person's retirement, a claim in a closed period or a new date in one, or a user not a
     * steward.
     */
    @Transactional
    public Claim redate(String sheetId, String user, Long id, LocalDate date) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimRedateForbiddenException(id, user);
        }
        if (!claim.getStatus().isOpen()) {
            throw new ClaimRedateException(id, "it is " + claim.getStatus()
                    + ", only an OPEN or PENDING claim can be re-dated");
        }
        LocalDate old = claim.getDate();
        // Neither out of nor into a closed period.
        periods.requireOpen(sheetId, old, date);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        person.requireClaimable(date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (settings.get(sheetId).numbersByPeriod()
                && !periods.nameOn(sheetId, old).equals(periods.nameOn(sheetId, date))) {
            claim.setNumber(nextNumber(sheetId, date));
        }
        claim.setDate(date);
        if (!claim.isCorrection()) {
            claim.setRoundingDate(date);
        }
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REDATED", user, person, saved) + numberOf(saved.getNumber())
                + " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Split an amount (more than 0.00 and less than its own) off an OPEN claim not deferred. The claim keeps the rest
     * and loses any figure a statement bound it to; a new OPEN claim of the same person with the amount split off, the
     * same date and note, the next number, opened by the user, due by its date plus the terms in force on that date,
     * names the claim's number as the one it was split from. Refused for any other status, a closed period, or a user
     * whose role may not open a claim.
     */
    @Transactional
    public Claim split(String sheetId, String user, Long id, BigDecimal amount) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!roles.may(sheetId, user, "open")) {
            throw new ClaimActionForbiddenException(id, user, "open");
        }
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimSplitException(id, "it is " + claim.getStatus() + ", only an OPEN claim can be split");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimSplitException(id, "it is deferred until " + claim.getDeferredUntil());
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ClaimSplitException(id, "the amount split off must be more than 0.00 and less than "
                    + claim.getAmount().toPlainString());
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.setStated(false, null);
        Claim kept = claims.saveAndFlush(claim);
        Claim part = new Claim(sheetId, kept.getPersonId(), amount, kept.getDate(), kept.getNote());
        part.setRoundingDate(kept.getRoundingDate());
        part.setDueBy(kept.getDate().plusDays(termsFor(sheetId, person, kept.getDate())));
        part.setListed(sheets.nextListed(sheetId));
        part.setStatus(ClaimStatus.OPEN);
        String number = nextNumber(sheetId, kept.getDate());
        part.setNumber(number);
        part.setOpenedBy(user);
        part.setSplitFrom(kept.getNumber());
        Claim saved = claims.saveAndFlush(part);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_SPLIT", user, person, kept) + numberOf(kept.getNumber())
                + " into=" + number);
        audit.recordOnCommit(record("CLAIM_OPENED", user, person, saved) + numberOf(number));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Add a proof to a claim, raising its proofs count by one. Refused for a SETTLED or VOID claim, or a closed period.
     */
    @Transactional
    public Claim addProof(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ClaimProofException(id, "it is " + claim.getStatus() + ", a proof cannot be added");
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", user, person, saved) + numberOf(saved.getNumber())
                + " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * A claim whose due (as the clerk limit weighs it) is above the proof threshold in force on its date cannot be
     * settled or approved while it has no proof; refused then. A due of n/a is within any threshold.
     */
    private void requireProof(String sheetId, Person person, Claim claim) {
        BigDecimal threshold = settings.get(sheetId).proofThresholdOn(claim.getDate());
        if (threshold == null || claim.getProofs() > 0) {
            return;
        }
        BigDecimal due = liveDue(sheetId, claim, figureOf(sheetId, person, claim));
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ClaimProofException(claim.getId(), "its due " + due.toPlainString()
                    + " is above the proof threshold " + threshold.toPlainString() + " and it has no proof");
        }
    }

    /**
     * Only a steward voids a SETTLED claim, and only within the days allowed after its settlement (the date of the last
     * SETTLED line of its status log) by the sheet's today; refused otherwise.
     */
    private void requireSettledVoidable(String sheetId, String user, Claim claim) {
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimActionForbiddenException(claim.getId(), user, "void a settled claim");
        }
        LocalDate settledOn = logOf(claim).stream()
                .filter(l -> l.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getDate)
                .reduce((first, last) -> last)
                .orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today.isAfter(settledOn.plusDays(SETTLED_VOID_DAYS))) {
            throw new ClaimVoidException(claim.getId(), "a settled claim can only be voided within "
                    + SETTLED_VOID_DAYS + " days of its settlement on " + settledOn);
        }
    }

    /** The PENDING claim a steward decides; refused for any other status, a closed period, or a user not a steward. */
    private Claim pendingForDecision(String sheetId, String user, Long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(id, claim.getStatus(), to);
        }
        periods.requireOpen(sheetId, claim.getDate());
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimApprovalForbiddenException(id, user, "only a steward approves or rejects a pending claim");
        }
        return claim;
    }

    /**
     * Fixes the due the claim shows as it settles: its figure (the one its statement binds it to, else worked out from
     * the rate on the claim's date) with its late charge, raised to the floor on its date, frozen in (a correction
     * freezes its bare figure); with no figure it stays live. Gives the due at the
     * end of its audit record.
     */
    private String settle(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        Sheet sheet = settings.get(sheetId);
        BigDecimal settledDue = figure == null || claim.isCorrection() ? figure
                : sheet.floored(figure.add(sheet.lateCharge(figure, claim.getDueBy(), claim.getDate())), claim.getDate());
        claim.setSettledDue(settledDue);
        return " due=" + (settledDue == null ? "n/a" : settledDue.toPlainString());
    }

    /** Logs the claim's status now: who changed it, the sheet's today, and its due as computed at this moment. */
    private void log(String sheetId, String user, Person person, Claim claim) {
        logs.save(new ClaimLog(sheetId, claim.getId(), claim.getStatus(), user, sheets.today(sheetId),
                shownDue(sheetId, person, claim)));
    }

    /**
     * The claim's due as shown: the one fixed when it was settled, else its live due; two decimals, or n/a.
     */
    private String shownDue(String sheetId, Person person, Claim claim) {
        BigDecimal due = claim.getSettledDue() != null ? claim.getSettledDue()
                : liveDue(sheetId, claim, figureOf(sheetId, person, claim));
        return due == null ? "n/a" : due.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** The figure under the claim's due: the one its statement binds it to now, else worked out from the rate. */
    private BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        return claim.isBound() ? claim.getStatedDue() : dueOf(sheetId, person, claim);
    }

    /**
     * A negative amount (a correction) must name, by number, a SETTLED claim of the same person; any other amount
     * names none. Refused otherwise. Gives the date of the claim a correction corrects (it rounds by that date), null
     * for any other claim.
     */
    private LocalDate requireCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ClaimCorrectionException("Only a correction (a negative amount) corrects a claim");
            }
            return null;
        }
        if (corrects == null) {
            throw new ClaimCorrectionException("A negative amount must name the settled claim it corrects");
        }
        return claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED)
                .map(Claim::getDate)
                .orElseThrow(() -> new ClaimCorrectionException(
                        "Claim " + corrects + " is not a settled claim of the same person"));
    }

    /**
     * The claim's amount at the rate its person's grade has on its date, two decimals rounded as the sheet's policy
     * date has the claim round (the rate figure, with no floor); null (n/a) with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        Sheet sheet = settings.get(sheetId);
        return rates.rateOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> sheet.rateStep(claim.getAmount(), pct, claim.getRoundingDate()))
                .orElse(null);
    }

    /**
     * The live due on a figure: the greater of the floor in force on the claim's date and the figure with the late
     * charge of an OPEN or PENDING claim overdue on the sheet's today added on top (no late charge on a claim deferred
     * on the sheet's today; a correction has neither floor nor late charge); null (n/a) stays null.
     */
    private BigDecimal liveDue(String sheetId, Claim claim, BigDecimal figure) {
        Sheet sheet = settings.get(sheetId);
        if (claim.isCorrection()) {
            return figure;
        }
        return sheet.floored(sheet.withLateCharge(figure, claim.getStatus().isOpen()
                && !claim.isDeferredOn(sheet.getToday()), claim.getDueBy(), claim.getDate()), claim.getDate());
    }

    /** The amount as shown (two decimals) and the date as yyyy-mm-dd. */
    private static String record(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " date=" + claim.getDate();
    }

    /** The claim's number at the end of an audit record, or nothing when it has none. */
    private static String numberOf(String number) {
        return number == null ? "" : " number=" + number;
    }

    /**
     * The number a claim dated on the date gets when opened: while the sheet numbers by period, the next in its
     * period's own sequence (X for no period); otherwise the next sheet-wide number.
     */
    private String nextNumber(String sheetId, LocalDate date) {
        if (settings.get(sheetId).numbersByPeriod()) {
            return sheets.nextSequenceNumber(sheetId, periods.nameOn(sheetId, date).orElse("X"));
        }
        return String.valueOf(sheets.nextClaimNumber(sheetId));
    }

    /** The terms of the person on the date: their own, else their grade's on that date, else the sheet's then. */
    private int termsFor(String sheetId, Person person, LocalDate date) {
        return person.getTerms() != null ? person.getTerms()
                : settings.termsFor(sheetId, person.gradeOn(date), date);
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
