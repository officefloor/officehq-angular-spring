package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet make: listed, added, edited and moved through their statuses. */
@Service
public class ClaimService {

    /** Shown in place of a due when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final Rollups rollups;
    private final PeriodService periods;
    private final RoleService roles;
    private final WatchService watches;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollups rollups, PeriodService periods, RoleService roles, WatchService watches, Audit audit) {
        this.watches = watches;
        this.periods = periods;
        this.roles = roles;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /**
     * Add a claim for a person on the sheet; with no date it is dated the sheet's entry date
     * while one is set, else the sheet's today. One due by in a
     * soft-closed period is a late entry, audited as LATE_ENTRY after its CLAIM_ADDED. Refused when
     * the due-by it would get (its date plus its terms) is inside a closed period, or when it is a correction that does not name a SETTLED claim
     * of the same person (see {@link #refuseUnlessCorrects}).
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : sheets.load(sheetId).defaultClaimDate();
        LocalDate dueBy = dueBy(sheets.lock(sheetId), person, dated);
        periods.refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, dated);
        refuseUnlessCorrects(sheetId, personId, amount, corrects);
        Claim added = new Claim(sheetId, personId, money(amount), dated, dueBy, clean(note), corrects);
        if (periods.softClosedOn(sheetId, dueBy)) {
            added.markLateEntry();
        }
        Claim claim = claims.saveAndFlush(added);
        claim = logged(claim, user, person);
        audited("CLAIM_ADDED", sheetId, user, person, claim);
        if (claim.isLateEntry()) {
            audited("LATE_ENTRY", sheetId, user, person, claim);
        }
        rollups.audit(sheetId, user, before);
        return claim;
    }

    /**
     * Change a claim's amount, date, note and the number it corrects; with no date it keeps its own. Only a DRAFT claim can be
     * edited: an OPEN one is changed by voiding it and adding a new one. It keeps the due-by fixed when it
     * was added, and so its period. Refused when the claim's due-by is inside a closed period, or when it
     * would be a correction that does not name a SETTLED claim of the same person.
     */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        refuseUnlessEditable(sheetId, user, claim);
        if (date != null) {
            refuseIfRetired(person(sheetId, claim.getPersonId()), date);
        }
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects);
        claim.change(money(amount), date != null ? date : claim.getDate(), clean(note), corrects);
        claim.lockFor(null);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Open a claim's editor for the user: the claim is locked for every other user until the editor is
     * saved or cancelled, or the sheet's today changes. Refused, taking no lock, when the claim cannot
     * be edited (see {@link #edit}) or another user's editor holds it.
     */
    @Transactional
    public Claim lock(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        refuseUnlessEditable(sheetId, user, claim);
        claim.lockFor(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel the user's editor of a claim, releasing its lock; a lock another user holds is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (user.equals(claim.getLockedBy())) {
            claim.lockFor(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** The sheet's today was changed: every editor's lock on its claims is released. */
    @EventListener
    public void todayChanged(TodayChanged change) {
        claims.unlockAll(change.sheetId());
    }

    /**
     * Refuse an edit of a claim that is not a DRAFT, that is due by inside a closed period, or that
     * another user's editor holds (423).
     */
    private void refuseUnlessEditable(String sheetId, String user, Claim claim) {
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.isLockedAgainst(user)) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "The claim is being edited by " + claim.getLockedBy() + ".");
        }
    }

    /**
     * Move a claim to a new status, recording the event; refused (and nothing written) when the
     * claim's current status does not allow the move. Settling fixes the claim's due at the rate
     * the person's grade has in force on the claim's date, or at the figure a statement bound it to,
     * with the late charge it has then frozen in.
     * Opening numbers the claim and records who opened it; a voided claim keeps its number, which
     * is never reused. The user who opened a claim
     * is refused when settling it. A clerk settling a claim whose due is above the sheet's clerk limit
     * (a due of n/a is within any limit) sends it PENDING instead, for a steward to approve or reject;
     * a steward settles any. A move the sheet's permission matrix does not allow the user's role is
     * refused. A claim due by inside a closed period cannot be moved, nor can a claim
     * deferred on the sheet's today be settled. A SETTLED claim is voided only by a steward, within
     * {@link ClaimStatus#VOID_SETTLED_DAYS} days of its settlement; its frozen figure leaves every
     * total and the audit records it as reversed.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next) {
        // Every move takes the sheet's lock before reading the claim, so concurrent opens on the
        // sheet are serialised: each sees the other's committed claim and number.
        LocalDate today = sheets.lock(sheetId).getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        String action = switch (next) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            case PENDING, DRAFT -> throw new IllegalArgumentException("No move to " + next + ".");
        };
        if (!roles.permits(sheetId, user, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " a claim.");
        }
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        boolean voidingSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidingSettled) {
            refuseVoidingSettled(sheetId, user, claim, today);
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ": it cannot be settled before then.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal settledDue = next == ClaimStatus.SETTLED
                ? dues.settling(claim, person.gradeOn(claim.getDate()))
                : null;
        if (next == ClaimStatus.OPEN) {
            refuseUnnoted(sheetId, claim, dues.due(claim, person.gradeOn(claim.getDate())));
        }
        if (next == ClaimStatus.SETTLED) {
            refuseUnproved(sheetId, claim, settledDue);
        }
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, settledDue)) {
            next = ClaimStatus.PENDING;
            settledDue = null;
        }
        claim.become(next);
        if (next == ClaimStatus.OPEN) {
            claim.number(periods.nextClaimNumber(sheetId, claim.getDueBy()));
            claim.openedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.settleDue(settledDue);
        }
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case PENDING -> "CLAIM_PENDING";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : voidingSettled
                ? " reversed=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : "";
        audited(event, sheetId, user, person, saved, saved.getNumber(), suffix);
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        }
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** Raise a claim's proofs count by one; refused when the claim is SETTLED or VOID. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot take a proof.");
        }
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_PROOF_ADDED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * Remind an OPEN or PENDING claim: the claim shows the sheet's today as its reminded date. Refused
     * when the claim is in any other status, or was already reminded on the sheet's today.
     */
    @Transactional
    public Claim remind(String sheetId, String user, long claimId) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded; only an OPEN or PENDING one can.");
        }
        if (today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded on " + today + ".");
        }
        claim.remind(today);
        Claim saved = claims.saveAndFlush(claim);
        audited("REMIND", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        return saved;
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it,
     * the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     * statement. Refused when the claim is not OPEN, when the date is not after the sheet's today, or
     * when the claim is due by inside a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred; only an OPEN one can.");
        }
        if (!until.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be deferred to a date after the sheet's today, " + today + ".");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        claim.defer(until);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_DEFERRED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " until=" + until);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Approve a PENDING claim: it becomes SETTLED, its due fixed at this moment at its live figure with
     * the late charge it has then. Only a steward approves, and not the user who opened the claim.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot approve it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal approvedDue = dues.approving(claim, person.gradeOn(claim.getDate()));
        refuseUnproved(sheetId, claim, approvedDue);
        claim.settleDue(approvedDue);
        claim.become(ClaimStatus.SETTLED);
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber(),
                " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Re-settle a SETTLED claim: its due is fixed again at the figure it would have today as an OPEN
     * claim bound by no statement (today's rates, grades, floor and late charge). Only a steward
     * re-settles; refused when the claim is not SETTLED or is due by inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim.");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        claim.settleDue(dues.resettling(claim, person.gradeOn(claim.getDate())));
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber(),
                " old=" + (old != null ? old.toPlainString() : NO_RATE)
                        + " new=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Reject a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     * statement when that statement included it. Only a steward rejects.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        claim.become(ClaimStatus.OPEN);
        Claim saved = logged(claims.saveAndFlush(claim), user, person(sheetId, claim.getPersonId()));
        audited("CLAIM_REJECTED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " reason=" + reason.trim());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Move a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its number and
     * date, and loses any figure a statement bound it to, its due live again until its new person's
     * next statement. Only a role the sheet's permission matrix allows (a steward by
     * default) reassigns. Refused when the claim is SETTLED or VOID, when it is
     * due by inside a closed period, when the new person is its own, is retired before its date, or (for
     * a correction) has no SETTLED claim of the number it corrects.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!roles.permits(sheetId, user, "reassign")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reassign a claim.");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, personId);
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s.");
        }
        refuseIfRetired(to, claim.getDate());
        refuseUnlessCorrects(sheetId, personId, claim.getAmount(), claim.getCorrects());
        claim.reassign(to.getId());
        claim.unbind();
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Re-date an OPEN or PENDING claim: it keeps the due-by fixed when it was added, and so its period
     * and number, and any figure a statement bound it to. Only a steward re-dates; refused when the claim
     * is not OPEN or PENDING, when its due-by is inside a closed period, or when the new date is after
     * its person's retirement.
     */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated; only an OPEN or PENDING one can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        refuseIfRetired(person, date);
        LocalDate old = claim.getDate();
        claim.redate(date);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REDATED", sheetId, user, person, saved, saved.getNumber(), " old=" + old + " new=" + date);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** What a split did: the original claim, as it is now, and the new claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /**
     * Split an amount (above 0.00 and below the claim's amount) off an OPEN claim: it keeps the rest
     * of its amount and loses any figure a statement bound it to; a new OPEN claim with the split-off
     * amount, the same date and note, the next number, opened by the user and due by a date fixed now
     * from the terms in force on its date, names the original's number as the one it was split from;
     * it is a late entry when that due-by is inside a soft-closed period. Refused when the claim is not
     * OPEN, is deferred on the sheet's today, or when its due-by or the new claim's is inside a closed
     * period.
     */
    @Transactional
    public Split split(String sheetId, String user, long claimId, BigDecimal amount) {
        Sheet sheet = sheets.lock(sheetId);
        LocalDate today = sheet.getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split; only an OPEN one can.");
        }
        if (claim.isDeferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ": it cannot be split.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        BigDecimal off = money(amount);
        if (off.signum() <= 0 || off.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The amount split off must be above 0.00 and below the claim's " + claim.getAmount().toPlainString() + ".");
        }
        Person person = person(sheetId, claim.getPersonId());
        LocalDate dueBy = dueBy(sheet, person, claim.getDate());
        periods.refuseIfClosed(sheetId, dueBy);
        Claim split = claim.split(off, dueBy);
        if (periods.softClosedOn(sheetId, dueBy)) {
            split.markLateEntry();
        }
        split.number(periods.nextClaimNumber(sheetId, split.getDueBy()));
        split.openedBy(user);
        Claim original = claims.saveAndFlush(claim);
        split = logged(claims.saveAndFlush(split), user, person);
        audited("CLAIM_SPLIT", sheetId, user, person, original, original.getNumber(), " into=" + split.getNumber());
        audited("CLAIM_OPENED", sheetId, user, person, split);
        rollups.audit(sheetId, user, before);
        return new Split(original, split);
    }

    /**
     * The PENDING claim a steward approves or rejects; refused when it is not PENDING, when its date
     * is inside a closed period, or when the user is not a steward.
     */
    private Claim pending(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not pending approval.");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim.");
        }
        return claim;
    }

    /**
     * Refuse settling or approving a claim with no proofs whose due is above the sheet's proof threshold
     * in force on the claim's date (a due of n/a is within any threshold).
     */
    private void refuseUnproved(String sheetId, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheets.load(sheetId).proofThresholdOn(claim.getDate());
        if (claim.getProofs() == 0 && threshold != null && due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the proof threshold of " + threshold.toPlainString()
                            + ": it needs a proof before it is settled.");
        }
    }

    /**
     * Refuse opening a claim with an empty note whose due is above the sheet's note threshold in force
     * on the claim's date (a due of n/a is within any threshold).
     */
    private void refuseUnnoted(String sheetId, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheets.load(sheetId).noteThresholdOn(claim.getDate());
        if ((claim.getNote() == null || claim.getNote().isBlank()) && threshold != null && due != null
                && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the note threshold of " + threshold.toPlainString()
                            + ": it needs a note before it is opened.");
        }
    }

    /**
     * Refuse voiding a SETTLED claim unless the user acts as a steward and the sheet's today is no more
     * than {@link ClaimStatus#VOID_SETTLED_DAYS} days after the claim's settlement in its status log.
     */
    private void refuseVoidingSettled(String sheetId, String user, Claim claim, LocalDate today) {
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward voids a SETTLED claim.");
        }
        LocalDate settledOn = claim.settledOn();
        if (settledOn == null || today == null || today.isAfter(settledOn.plusDays(ClaimStatus.VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A SETTLED claim can only be voided within "
                    + ClaimStatus.VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** Whether a clerk is settling a claim whose due is above the sheet's clerk limit. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return Role.CLERK.equals(roles.roleOf(sheetId, user));
    }

    /**
     * Refuse a correction (an amount below 0.00) unless it names, by number, a SETTLED claim of the
     * same person; and refuse naming a claim to correct on any other claim.
     */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a correction, with an amount below 0.00, names a claim it corrects.");
            }
            return;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(corrected -> corrected.getStatus() == ClaimStatus.SETTLED)
                .isPresent();
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction must name the number of a SETTLED claim of the same person.");
        }
    }

    /** Refuse a claim dated after its person's retirement. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (!person.canClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is retired from " + person.getRetiredFrom() + ": a claim cannot be dated after it.");
        }
    }

    private Claim claim(String sheetId, long claimId) {
        return claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
    }

    /**
     * The date a claim of the person dated on a date is due by: its date plus the first of the
     * person's own terms, their grade's terms and the sheet's terms in force on it.
     */
    private static LocalDate dueBy(Sheet sheet, Person person, LocalDate date) {
        Integer own = person.getTerms();
        return date.plusDays(own != null ? own : sheet.termsFor(person.gradeOn(date), date));
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    /**
     * Add a line to the claim's status log for the status it now has: by the user, on the sheet's
     * today, with its due as computed now.
     */
    private Claim logged(Claim claim, String user, Person person) {
        claim.logged(user, sheets.today(claim.getSheetId()), dues.due(claim, person.gradeOn(claim.getDate())));
        return claims.saveAndFlush(claim);
    }

    /** Record the change to the claim once it is committed; a claim with a number carries it. */
    private void audited(String event, String sheetId, String user, Person person, Claim claim) {
        audited(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    private void audited(String event, String sheetId, String user, Person person, Claim claim, String number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /** The due figure a claim shows, without its late charge (see {@link Dues#figure}). */
    public BigDecimal figure(Claim claim, String grade) {
        return dues.figure(claim, grade);
    }

    /** The due a claim shows, with its late charge (see {@link Dues#due}). */
    public BigDecimal due(Claim claim, String grade) {
        return dues.due(claim, grade);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }
}
