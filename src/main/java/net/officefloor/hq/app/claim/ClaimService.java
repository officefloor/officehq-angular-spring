package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.setting.Permission;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final PersonService persons;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodService periods;
    private final SettingService settings;
    private final Audit audit;
    private final TransactionTemplate transaction;

    public ClaimService(ClaimRepository claims, PersonRepository people, PersonService persons, RollupService rollup, SheetService sheets,
            PeriodService periods, SettingService settings, Audit audit, TransactionTemplate transaction) {
        this.transaction = transaction;
        this.claims = claims;
        this.people = people;
        this.persons = persons;
        this.rollup = rollup;
        this.sheets = sheets;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a claim without a date is dated the sheet's today. One dated in a closed period
     * is refused, one dated in a soft-closed period is a late entry, and so is a correction that does not name a settled claim of the same person. */
    @Transactional
    public Claim add(String user, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        LocalDate today = sheets.today(sheetId);
        Person person = requirePerson(sheetId, personId);
        requireNotRetired(person, date != null ? date : today);
        requireOpenPeriod(sheetId, date != null ? date : today);
        String corrected = correctedBy(sheetId, personId, amount, corrects);
        Snapshot before = rollup.snapshot(sheetId);
        LocalDate dated = date != null ? date : today;
        LocalDate dueBy = dueBy(sheetId, person, dated);
        Claim fresh = new Claim(sheetId, personId, amount, dated, dueBy, noteOf(note));
        fresh.setCorrects(corrected);
        fresh.setLateEntry(periods.periodOf(sheetId, dated).filter(Period::isSoftClosed).isPresent());
        Claim claim = claims.saveAndFlush(fresh);
        logStatus(user, today, claim, person);
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, claim));
        if (claim.isLateEntry()) {
            audit.recordOnCommit(entry("LATE_ENTRY", user, person, claim));
        }
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Edit a claim; only a draft can be edited (an open claim is voided and added again instead), and one dated
     * in a closed period, or that would be, is refused, as is a correction that does not name a settled claim of
     * the same person. */
    @Transactional
    public Claim update(String user, String sheetId, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        requireOpenPeriod(sheetId, claim.getDate());
        requireNotLockedByOther(user, today, claim);
        requireOpenPeriod(sheetId, date != null ? date : today);
        requireNotRetired(requirePerson(sheetId, claim.getPersonId()), date != null ? date : today);
        String corrected = correctedBy(sheetId, claim.getPersonId(), amount, corrects);
        Snapshot before = rollup.snapshot(sheetId);
        // Saving the editor releases its lock.
        claim.unlock();
        claim.setAmount(amount);
        claim.setCorrects(corrected);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, requirePerson(sheetId, claim.getPersonId()), claim));
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Open a claim's editor: the claim is locked for the user until the editor is saved or cancelled, or the
     * sheet's today changes. A claim that cannot be edited (not a draft, or dated in a closed period), or that
     * another user has locked, is refused and takes no lock. Opening it releases the user's lock on any other
     * claim of the same person, whose editor it replaces. */
    @Transactional
    public Claim lock(String user, String sheetId, Long id) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        requireOpenPeriod(sheetId, claim.getDate());
        requireNotLockedByOther(user, today, claim);
        for (Claim other : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (!other.getId().equals(id) && other.getPersonId().equals(claim.getPersonId())
                    && user.equals(other.getLockedBy())) {
                other.unlock();
            }
        }
        claim.lock(user, today);
        claims.flush();
        return claim;
    }

    /** Cancel a claim's editor: the user's lock on it is released. A lock another user holds is left as it is. */
    @Transactional
    public Claim unlock(String user, String sheetId, Long id) {
        sheets.today(sheetId); // the sheet must exist
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (user.equals(claim.getLockedBy())) {
            claim.unlock();
            claims.flush();
        }
        return claim;
    }

    /** Refuse a change to a claim whose editor another user has open on the sheet's today. */
    private static void requireNotLockedByOther(String user, LocalDate today, Claim claim) {
        String holder = claim.lockHolderOn(today);
        if (holder != null && !holder.equals(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is being edited by " + holder + ".");
        }
    }

    /** Move a claim to another status; a change its status does not allow, or of a claim dated in a closed
     * period, is refused. A settle by a clerk of a claim whose due is above the clerk limit sends it PENDING a
     * steward instead. */
    @Transactional
    public Claim changeStatus(String user, String sheetId, Long id, ClaimStatus next) {
        sheets.today(sheetId); // the sheet must exist
        // Locking the sheet before taking the number opens claims one at a time; a refused open rolls back
        // and gives the number back.
        if (next == ClaimStatus.OPEN) {
            sheets.lock(sheetId);
        }
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        String number = next == ClaimStatus.OPEN ? nextNumber(sheetId, claim) : null;
        requireOpenPeriod(sheetId, claim.getDate());
        // A steward may void a SETTLED claim within the days allowed after it was settled.
        boolean unsettle = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (!unsettle && !claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (unsettle) {
            requireVoidableSettled(user, sheetId, claim);
        } else {
            // Only a role the sheet's permission matrix allows may open, settle or void a claim.
            settings.requirePermitted(sheetId, user, PERMISSIONS.get(next));
        }
        // A deferred claim cannot be settled until the sheet's today reaches the date it is deferred until.
        if (next == ClaimStatus.SETTLED && claim.deferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        // Four eyes: whoever opened a claim cannot also settle it.
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        BigDecimal due = rollup.dueOf(claim, person);
        // A claim whose due is above the proof threshold in force on its date needs a proof before it is settled,
        // whoever settles it, so it never goes PENDING without one.
        // A claim whose due is above the note threshold in force on its date cannot be opened with an empty note.
        if (next == ClaimStatus.OPEN) {
            settings.requireNote(sheetId, claim.getDate(), due, claim.getNote());
        }
        if (next == ClaimStatus.SETTLED) {
            settings.requireProof(sheetId, claim.getDate(), due, claim.getProofs());
        }
        // A clerk settles only a claim whose due is within the sheet's clerk limit; above it, a steward must
        // approve it.
        if (next == ClaimStatus.SETTLED && !settings.maySettle(sheetId, user, due)) {
            next = ClaimStatus.PENDING;
        }
        Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        // A voided claim's line carries the number it had.
        String entry = entry(EVENTS.get(next), user, person, claim);
        if (unsettle) {
            // The frozen figure leaves every total; the claim keeps it, and its number.
            entry += " reversed=" + figure(claim.getSettledDue());
        }
        if (next == ClaimStatus.SETTLED) {
            // Settling fixes the due at the figure the claim has: the one its last statement bound it to, else
            // the rate its grade has on its date; with no figure it stays live. The late charge it has (taken
            // while it is still OPEN) is frozen in.
            entry += settle(claim, due);
        }
        logStatus(user, sheets.today(sheetId), claim, person);
        claims.flush();
        audit.recordOnCommit(entry);
        if (next == ClaimStatus.SETTLED) {
            notifyWatchers(user, person, claim);
        }
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** A steward approves a PENDING claim: it is SETTLED at the due it has now, its live figure with any late
     * charge, frozen. The user who opened it cannot approve it, and one whose due is above the proof threshold
     * in force on its date cannot be approved while it has no proof. */
    @Transactional
    public Claim approve(String user, String sheetId, Long id) {
        Claim claim = requirePending(user, sheetId, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal due = rollup.dueOf(claim, person);
        settings.requireProof(sheetId, claim.getDate(), due, claim.getProofs());
        claim.setStatus(ClaimStatus.SETTLED);
        String entry = entry("CLAIM_APPROVED", user, person, claim) + settle(claim, due);
        logStatus(user, sheets.today(sheetId), claim, person);
        claims.flush();
        audit.recordOnCommit(entry);
        notifyWatchers(user, person, claim);
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** A steward re-settles a SETTLED claim: it is frozen again at the figure it would have today as an OPEN
     * claim bound by no statement, at today's rates, grades, floor and late charge. Refused for anyone else, a
     * claim of any other status, or one dated in a closed period. */
    @Transactional
    public Claim resettle(String user, String sheetId, Long id) {
        sheets.today(sheetId); // the sheet must exist
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a SETTLED claim can be re-settled.");
        }
        settings.requireSteward(sheetId, user);
        Person person = requirePerson(sheetId, claim.getPersonId());
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal old = rollup.dueOf(claim, person);
        BigDecimal now = rollup.openDueOf(claim, person);
        claim.setSettledDue(now);
        logStatus(user, sheets.today(sheetId), claim, person);
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_RESETTLED", user, person, claim) + " old=" + figure(old) + " new="
                + figure(now));
        notifyWatchers(user, person, claim);
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Refuse to void a SETTLED claim unless the user is a steward and the sheet's today is within
     * {@link #VOID_SETTLED_DAYS} days of the day the claim was settled, as its status log records it. */
    private void requireVoidableSettled(String user, String sheetId, Claim claim) {
        settings.requireSteward(sheetId, user);
        LocalDate settledOn = null;
        for (StatusLogLine line : claim.getStatusLog()) {
            if (line.getStatus() == ClaimStatus.SETTLED) {
                settledOn = line.getOn();
            }
        }
        if (settledOn == null || sheets.today(sheetId).isAfter(settledOn.plusDays(VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A settled claim can be voided only within " + VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** The days after its settlement a steward may still void a SETTLED claim. */
    private static final int VOID_SETTLED_DAYS = 7;

    /** Notify each user watching the claim's person, in the order they started watching, that it was settled. */
    private void notifyWatchers(String user, Person person, Claim claim) {
        for (String watcher : persons.watchers(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + claim.getSheetId() + " by=" + user + " user=" + watcher
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    /** A figure with two decimals, or n/a. */
    private static String figure(BigDecimal due) {
        return due != null ? due.setScale(2).toPlainString() : "n/a";
    }

    /** Defer an OPEN claim until a date after the sheet's today: until then it counts in no total, is not overdue,
     * cannot be settled and is left unbound by a statement. A claim that is not OPEN, a date not after today, or
     * a claim dated in a closed period, is refused. */
    @Transactional
    public Claim defer(String user, String sheetId, Long id, LocalDate until) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OPEN claim can be deferred.");
        }
        if (!until.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A claim is deferred to a date after today.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_DEFERRED", user, person, claim) + " until=" + until);
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure a statement bound
     * it to stays bound; its period follows the new date and, with period numbering, a claim whose period changes
     * takes the next number of its new period's sequence, its old number never given out again. A claim of any
     * other status, one dated in a closed period or a new date inside one, a date after its person's retirement,
     * or anyone but a steward, is refused. */
    @Transactional
    public Claim redate(String user, String sheetId, Long id, LocalDate date) {
        sheets.today(sheetId); // the sheet must exist
        // Locking the sheet before taking the number numbers claims one at a time.
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        settings.requireSteward(sheetId, user);
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OPEN or PENDING claim can be re-dated.");
        }
        requireOpenPeriod(sheetId, claim.getDate());
        requireOpenPeriod(sheetId, date);
        Person person = requirePerson(sheetId, claim.getPersonId());
        requireNotRetired(person, date);
        Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        boolean periodChanged = !periods.periodOf(sheetId, old).map(Period::getId)
                .equals(periods.periodOf(sheetId, date).map(Period::getId));
        claim.setDate(date);
        if (periodChanged && claim.getNumber() != null && sheets.numbersByPeriod(sheetId)) {
            claim.renumber(nextNumber(sheetId, claim));
        }
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_REDATED", user, person, claim) + " old=" + old + " new=" + date);
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Split an amount off an OPEN claim: more than 0.00 and less than its amount. The claim keeps the rest and
     * loses any figure a statement bound it to; a new OPEN claim of the amount split off, with the same date and
     * note, takes the next number, is opened by the user, has its due-by fixed now from the terms in force on the
     * date, and names the claim it was split from. A claim that is not OPEN (PENDING, settled, ...), is deferred,
     * or is dated in a closed period, is refused. */
    @Transactional
    public Split split(String user, String sheetId, Long id, BigDecimal amount) {
        LocalDate today = sheets.today(sheetId);
        // Locking the sheet before taking the number numbers claims one at a time; a refused split rolls back and
        // gives the number back.
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an OPEN claim can be split.");
        }
        if (claim.deferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The amount split off is more than 0.00 and less than the claim's amount.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unbind(today);
        LocalDate dated = claim.getDate();
        LocalDate dueBy = dueBy(sheetId, person, dated);
        Claim fresh = new Claim(sheetId, person.getId(), amount, dated, dueBy, claim.getNote());
        fresh.setSplitFrom(claim.getNumber());
        fresh.setStatus(ClaimStatus.OPEN);
        fresh.setNumber(nextNumber(sheetId, fresh));
        fresh.setOpenedBy(user);
        Claim split = claims.saveAndFlush(fresh);
        logStatus(user, today, split, person);
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_SPLIT", user, person, claim) + " into=" + split.getNumber());
        audit.recordOnCommit(entry("CLAIM_OPENED", user, person, split));
        rollup.recordChanges(user, sheetId, before);
        return new Split(claim, split);
    }

    /** What a split did: the claim split, with what it keeps, and the new claim split off it. */
    public record Split(Claim claim, Claim into) {}

    /** Raise a claim's proofs count by one; a SETTLED or VOID claim, or one dated in a closed period, is
     * refused. */
    @Transactional
    public Claim addProof(String user, String sheetId, Long id) {
        sheets.today(sheetId); // the sheet must exist
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot take a proof.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        claim.addProof();
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_PROOF_ADDED", user, person, claim) + " proofs=" + claim.getProofs());
        return claim;
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     * statement if that statement included it. */
    @Transactional
    public Claim reject(String user, String sheetId, Long id, String reason) {
        Claim claim = requirePending(user, sheetId, id);
        Person person = requirePerson(sheetId, claim.getPersonId());
        Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(user, sheets.today(sheetId), claim, person);
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_REJECTED", user, person, claim) + " reason=" + reason.trim());
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** A user whose role may reassign (a steward by the standing rule) moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and
     * date but loses any figure a statement bound it to: it is live again until its new person's next statement.
     * One dated in a closed period, a correction (which names a settled claim of its own person), or one dated
     * after its new person is retired, is refused. */
    @Transactional
    public Claim reassign(String user, String sheetId, Long id, Long toPersonId) {
        sheets.today(sheetId); // the sheet must exist
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        settings.requirePermitted(sheetId, user, Permission.REASSIGN);
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be moved.");
        }
        if (claim.isCorrection()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A correction cannot be moved.");
        }
        Person from = requirePerson(sheetId, claim.getPersonId());
        Person to = requirePerson(sheetId, toPersonId);
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim is already that person's.");
        }
        if (to.retiredOn(claim.getDate())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    to.getName() + " is retired from " + to.getRetiredFrom() + ".");
        }
        Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind(sheets.today(sheetId));
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_REASSIGNED", user, to, claim) + " from=" + from.getName() + " to="
                + to.getName());
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** The PENDING claim a steward acts on; refused when it is not on the sheet, not PENDING, dated in a closed
     * period, or the user is not a steward. */
    private Claim requirePending(String user, String sheetId, Long id) {
        sheets.today(sheetId); // the sheet must exist
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a PENDING claim can be approved or rejected.");
        }
        settings.requireSteward(sheetId, user);
        return claim;
    }

    /** Log the status a claim has now, by the user on the sheet's today, with its due as computed now. */
    private void logStatus(String user, LocalDate today, Claim claim, Person person) {
        BigDecimal due = rollup.dueOf(claim, person);
        claim.logStatus(user, today, due != null ? due.setScale(2).toPlainString() : "n/a");
    }

    /** Fix a claim's due at the given figure (none stays live), giving the end of its audit line. */
    private static String settle(Claim claim, BigDecimal due) {
        claim.setSettledDue(due);
        return " due=" + (due != null ? due.toPlainString() : "n/a");
    }

    /** What opening (or settling) claims together did: how many went through and were refused, and the claims
     * that went through. */
    public record OpenedAll(int opened, int refused, List<Claim> claims) {}

    /** Open the given claims of the sheet one at a time, in list order: people in the order they were added,
     * each one's claims newest first. Each is opened, and numbered, as {@link #changeStatus} would open it on its
     * own; one that cannot be opened (or is not on the sheet) is refused and left as it is. */
    public OpenedAll openAll(String user, String sheetId, List<Long> ids) {
        return changeAll(user, sheetId, ids, ClaimStatus.OPEN);
    }

    /** Settle the given claims of the sheet one at a time, in list order, each as {@link #changeStatus} would
     * settle it on its own: one a clerk sends PENDING counts as settled; one that cannot be settled (or is not on
     * the sheet) is refused and left as it is. */
    public OpenedAll settleAll(String user, String sheetId, List<Long> ids) {
        return changeAll(user, sheetId, ids, ClaimStatus.SETTLED);
    }

    /** Move the given claims to a status one at a time, in list order: people in the order they were added, each
     * one's claims newest first, each in its own transaction; one that is refused is left as it is. */
    private OpenedAll changeAll(String user, String sheetId, List<Long> ids, ClaimStatus next) {
        Set<Long> wanted = new HashSet<>(ids);
        List<Claim> chosen = transaction.execute(status -> {
            sheets.today(sheetId); // the sheet must exist
            Map<Long, Integer> personOrder = new HashMap<>();
            for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
                personOrder.put(person.getId(), personOrder.size());
            }
            return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                    .filter(c -> wanted.contains(c.getId()))
                    .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                    .toList();
        });
        List<Claim> changed = new ArrayList<>();
        if (settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: every claim is refused.
            return new OpenedAll(0, wanted.size(), changed);
        }
        for (Claim claim : chosen) {
            try {
                changed.add(transaction.execute(status -> changeStatus(user, sheetId, claim.getId(), next)));
            } catch (ResponseStatusException refused) {
                // Left as it is: its transaction rolled back, writing nothing.
            }
        }
        return new OpenedAll(changed.size(), wanted.size() - changed.size(), changed);
    }

    /** The sequence a claim in no period is numbered in, with period numbering. */
    private static final String NO_PERIOD = "X";

    /** The action of the permission matrix each status a claim is moved to takes. */
    private static final Map<ClaimStatus, Permission> PERMISSIONS = Map.of(
            ClaimStatus.OPEN, Permission.OPEN,
            ClaimStatus.SETTLED, Permission.SETTLE,
            ClaimStatus.VOID, Permission.VOID);

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED",
            ClaimStatus.PENDING, "CLAIM_PENDING",
            ClaimStatus.SETTLED, "CLAIM_SETTLED",
            ClaimStatus.VOID, "CLAIM_VOIDED");

    /** The figure a claim has now: what it was settled at, else what its person's last statement bound it to,
     * else what is due at the rate of the grade its person has on its date; none when that is n/a. */
    public BigDecimal figureOf(Claim claim, Person person) {
        return rollup.figureOf(claim, person);
    }

    /** The number a claim opened now takes: the sheet's next number or, with period numbering in force, the next
     * in its period's own sequence, <period name>-<n>, or X-<n> for a claim in no period. */
    private String nextNumber(String sheetId, Claim claim) {
        if (!sheets.numbersByPeriod(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
        }
        String prefix = periods.periodOf(sheetId, claim.getDate()).map(Period::getName).orElse(NO_PERIOD) + "-";
        int last = 0;
        for (Claim other : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            // A number a re-dated claim had before is never given out again.
            List<String> taken = new ArrayList<>(other.getFormerNumbers());
            taken.add(other.getNumber());
            for (String number : taken) {
                if (number != null && number.startsWith(prefix)) {
                    String rest = number.substring(prefix.length());
                    if (rest.matches("\\d{1,9}")) {
                        last = Math.max(last, Integer.parseInt(rest));
                    }
                }
            }
        }
        return prefix + (last + 1);
    }

    /** The number a claim of this amount corrects: none unless the amount is negative, when it must name a
     * SETTLED claim of the same person, or the claim is refused. */
    private String correctedBy(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        String named = corrects == null ? null : corrects.trim();
        boolean settled = named != null && claims.findBySheetIdAndPersonId(sheetId, personId).stream()
                .anyMatch(c -> named.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction must name a settled claim of the same person.");
        }
        return named;
    }

    /** Refuse a change to a claim dated in a closed period. Locks the sheet first, so a period cannot close
     * while the change is made. */
    private void requireOpenPeriod(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.requireOpen(sheetId, date);
    }

    /** Refuse a claim dated after its person's retirement. */
    private static void requireNotRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    person.getName() + " is retired from " + person.getRetiredFrom() + ".");
        }
    }

    /** The due-by fixed for a claim of the person dated {@code dated}: its date plus the person's own terms, else
     * the terms in force then for the grade the person has then, else the sheet's terms then. */
    private LocalDate dueBy(String sheetId, Person person, LocalDate dated) {
        Integer own = person.getTerms();
        return dated.plusDays(own != null ? own : settings.termsFor(sheetId, person.gradeOn(dated), dated));
    }

    private Person requirePerson(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** The amount is written as the screen shows it, with two decimals; the date as yyyy-mm-dd; a
     * claim with a number ends with it. */
    private static String entry(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (claim.getNumber() != null ? " number=" + claim.getNumber() : "");
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
