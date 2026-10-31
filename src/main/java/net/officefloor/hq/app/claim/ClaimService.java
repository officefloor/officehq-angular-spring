package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.watch.WatchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet have made. */
@Service
public class ClaimService {

    /** The prefix of the numbers given within no period. */
    private static final String NO_PERIOD = "X";

    /** How many days after its settlement a steward may still void a SETTLED claim. */
    static final int SETTLED_VOID_DAYS = 7;

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RateRepository rates;
    private final Audit audit;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final ClaimSequenceRepository sequences;
    private final WatchService watches;
    private final HolidayService holidays;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, RateRepository rates, Audit audit, Rollup rollup, PeriodRepository periods,
            RoleService roles, ClaimSequenceRepository sequences, WatchService watches,
            HolidayService holidays) {
        this.holidays = holidays;
        this.watches = watches;
        this.periods = periods;
        this.sequences = sequences;
        this.roles = roles;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.rates = rates;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Every claim on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** The ids of those given that are claims on the sheet, in list order: people in the order they are listed,
     *  each person's claims newest first. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparing(Claim::getPersonId))
                .map(Claim::getId)
                .toList();
    }

    /** Add a claim for a person; a missing date takes the sheet's entry date while set, else its today, a missing note is empty. Its period is the one its due-by
     *  (the terms-th business day after its date, on the terms it gets, skipping weekends and
     *  the sheet's holidays) is in: one due by inside a soft-closed period is a late entry. Refused
     *  when its due-by is inside a closed period, when it is a correction that does not name a SETTLED claim of
     *  the person, or when its external reference (trimmed; a blank one is none) is already used on the sheet,
     *  without regard to case. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        LocalDate today = sheet.getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate claimDate = date != null ? date : sheet.defaultClaimDate();
        LocalDate dueBy = holidays.dueBy(sheetId, claimDate, termsOf(sheet, person, claimDate));
        refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, claimDate);
        refuseUnlessCorrects(sheetId, personId, amount, corrects);
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCase(sheetId, reference)) {
            throw refUsed(reference);
        }
        Rollup.Figures before = rollup.of(sheetId);
        Claim claim = new Claim(sheetId, personId, scaled(amount), claimDate, dueBy, noteOf(note), corrects,
                claims.lastListed(sheetId) + 1);
        boolean late = periods.existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                sheetId, dueBy, dueBy);
        if (late) {
            claim.markLateEntry();
        }
        claim.setRef(reference);
        Claim added = claims.saveAndFlush(claim);
        added.logStatus(user, today, shownDue(sheet, added, person));
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_ADDED", user, person, added, added.getNumber())
                + (reference == null ? "" : " ref=" + reference));
        if (late) {
            audit.recordChangeOnCommit(sheetId, user, entry("LATE_ENTRY", user, person, added, null));
        }
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** Change a claim's amount, date, note and the claim it corrects; a missing date takes the sheet's today. Only
     *  a draft can be changed: to change an open claim, void it and add a new one. Its due-by stays as it was fixed.
     *  Refused when its due-by is inside a closed period, or when it would be a correction that does not name a
     *  SETTLED claim of its person, when its external reference is already used by another claim on the sheet, or
     *  while another user has its editor open. Saving releases its lock. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        refuseIfLockedByOther(claim, user);
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(), date != null ? date : today);
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects);
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCaseAndIdNot(sheetId, reference, claim.getId())) {
            throw refUsed(reference);
        }
        Rollup.Figures before = rollup.of(sheetId);
        claim.setAmount(scaled(amount));
        claim.setRef(reference);
        claim.setCorrects(corrects);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claim.setLockedBy(null);
        Claim edited = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(edited.getPersonId(), sheetId).orElseThrow();
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_EDITED", user, person, edited, edited.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /**
     * Move a claim to a new status; a move its current status does not allow is refused. Opening a
     * claim gives it its next number (the sheet's next, or the next in its period while the sheet numbers per
     * period) and records who opened it; voiding it keeps its number, which is
     * never given again; settling it fixes its due: the figure a statement binds it to, else its amount
     * at the rate in force on its date of its person's grade in force on that date, as rates are now, with the late charge it has gained
     * added on top, so later rate changes leave it as it is. The user
     * who opened a claim cannot settle it, and a clerk settling it when that due is above the sheet's clerk limit
     * makes it PENDING instead, for a steward to approve or reject (a claim with no due is within any limit); a
     * steward settles anything. Opening, settling and voiding are each refused for a role the sheet's permission
     * matrix does not allow. Only a steward may void a SETTLED claim, and only within {@value #SETTLED_VOID_DAYS}
     * days of the day its log records it settled, by the sheet's today; its frozen figure then leaves every total
     * and the audit line ends with it as reversed. A claim due by inside a closed period cannot move. The sheet is locked
     * first, so moves on one sheet are made one at a time and no number is given twice.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        String action = switch (next) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            default -> null;
        };
        if (action != null && !sheet.allows(action, roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " a claim");
        }
        boolean voidsSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidsSettled) {
            refuseSettledVoid(sheet, user, claim);
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheet.getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = dueNow(sheet, claim, person);
        if (next == ClaimStatus.SETTLED) {
            refuseUnproven(sheet, claim, due);
        } else if (next == ClaimStatus.OPEN) {
            refuseUnnoted(sheet, claim, due);
        }
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheet, user, due)) {
            next = ClaimStatus.PENDING;
        }
        Rollup.Figures before = rollup.of(sheetId);
        String number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = nextNumber(sheet, claim.getDueBy());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String line = entry(event, user, person, changed, number);
        if (next == ClaimStatus.SETTLED) {
            line += " due=" + dueText(changed);
        } else if (voidsSettled) {
            line += " reversed=" + dueText(changed);
        }
        audit.recordChangeOnCommit(sheetId, user, line);
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** Defer an OPEN claim until a day after the sheet's today: until the sheet's today reaches that day it counts
     *  in no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for a claim that
     *  is not OPEN, a day not after today and a claim due by inside a closed period. */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred; only an OPEN one can");
        }
        if (!until.isAfter(sheet.getToday())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A claim is deferred to a day after today");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setDeferredUntil(until);
        Claim deferred = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_DEFERRED", user, person, deferred, deferred.getNumber()) + " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return deferred;
    }

    /** A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed, and with it its period and its
     *  number, and a figure a statement binds it to stays bound. Refused for anyone but a steward, a claim in any
     *  other status, a claim due by inside a closed period and a new date after its person retired. */
    @Transactional
    public Claim redate(String sheetId, String user, long id, LocalDate date) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated; only an OPEN or PENDING one can");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        refuseIfRetired(person, date);
        Rollup.Figures before = rollup.of(sheetId);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        Claim redated = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_REDATED", user, person, redated, redated.getNumber())
                + " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return redated;
    }

    /** Open a claim's editor for this user, locking the claim for other users until they save or cancel it or the
     *  sheet's today changes. Refused for a claim that is not a draft, one due by inside a closed period and one
     *  another user has locked; a refused claim takes no lock. Opening it again for the same user keeps the lock. */
    @Transactional
    public Claim lock(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfLockedByOther(claim, user);
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel this user's claim editor, releasing their lock on the claim; another user's lock is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** Refuse a change to a claim while another user has its editor open. */
    private static void refuseIfLockedByOther(Claim claim, String user) {
        if (claim.getLockedBy() != null && !claim.getLockedBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That claim is being edited by " + claim.getLockedBy());
        }
    }

    /** Raise a claim's proofs count by one. Refused for a SETTLED or VOID claim and one due by inside a closed
     *  period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a proof");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.addProof();
        Claim proven = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_PROOF_ADDED", user, person, proven, proven.getNumber())
                + " proofs=" + proven.getProofs());
        return proven;
    }

    /** Write a reminder for an OPEN or PENDING claim, dated the sheet's today. Refused for a claim in any other
     *  status and one already reminded on the sheet's today. */
    @Transactional
    public Claim remind(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded; only an OPEN or PENDING one can");
        }
        if (sheet.getToday().equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded today");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.remind(sheet.getToday());
        Claim reminded = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("REMIND", user, person, reminded, reminded.getNumber()));
        return reminded;
    }

    /** A steward approves a PENDING claim: it becomes SETTLED, its due fixed as it is at this moment (its live
     *  figure with the late charge it has gained). Refused for a clerk, for the user who opened the claim, for a
     *  claim that is not PENDING and for one due by inside a closed period. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = pendingClaim(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = dueNow(sheet, claim, person);
        refuseUnproven(sheet, claim, due);
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_APPROVED", user, person, changed, changed.getNumber())
                + " due=" + dueText(changed));
        watches.notifyWatchers(sheetId, user, person, changed.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     *  statement if that statement included it. Refused for a clerk, for a claim that is not PENDING and for one
     *  due by inside a closed period. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = pendingClaim(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_REJECTED", user, person, changed, changed.getNumber())
                + " reason=" + reason.trim());
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward re-settles a SETTLED claim: its due is fixed again at the figure it would have today as an open
     *  claim bound by no statement (its live figure at today's rates, grades and floor, with the late charge it
     *  would have gained by today on top), and its status log gains a SETTLED line. Refused for a clerk, for a
     *  claim that is not SETTLED and for one due by inside a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal live = liveNow(sheet, claim, person);
        BigDecimal due = live == null ? null : floored(claim, live.add(lateChargeAsOpen(claim, live, sheet, person.gradeOn(claim.getDate()))), sheet);
        Rollup.Figures before = rollup.of(sheetId);
        String old = dueText(claim);
        claim.setSettledDue(due);
        claim.logStatus(user, sheet.getToday(), due);
        Claim changed = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_RESETTLED", user, person, changed, changed.getNumber())
                + " old=" + old + " new=" + dueText(changed));
        watches.notifyWatchers(sheetId, user, person, changed.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A user whose role may reassign moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and date,
     *  loses any figure a statement bound it to (it is live again until its new person's next statement), and is
     *  listed as its new person's newest claim. Refused for a role the permission matrix does not allow (by default a clerk), for a SETTLED or VOID claim, for a move to the
     *  person it is already of, for one due by inside a closed period or after the new person retired, and for a
     *  correction the new person has no SETTLED claim of that number for. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (!sheet.allows("reassign", roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reassign a claim");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (claim.getPersonId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is already " + to.getName() + "'s");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfRetired(to, claim.getDate());
        refuseUnlessCorrects(sheetId, to.getId(), claim.getAmount(), claim.getCorrects());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.unbind();
        claim.reassign(to.getId(), claims.lastListed(sheetId) + 1);
        Claim moved = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_REASSIGNED", user, to, moved, moved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return moved;
    }

    /** A claim split in two: the original, keeping the rest of its amount, and the new claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /** Split an amount (more than 0.00 and less than its amount) off an OPEN claim: it keeps the rest and loses any
     *  figure a statement bound it to; a new OPEN claim of the same person with the amount split off, the same date
     *  and note, the next number, opened by this user, a due-by fixed now from the terms in force on the date and
     *  naming the original as the claim it was split from, is listed as the person's newest; it is a late entry when
     *  the original is, and is numbered within the period of its own due-by. Refused for a claim
     *  that is not OPEN or is deferred, one due by inside a closed period, an amount out of range, and a role the
     *  permission matrix does not allow to open a claim. */
    @Transactional
    public Split split(String sheetId, String user, long id, BigDecimal amount) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN || claim.isDeferredOn(sheet.getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split; only an OPEN one not deferred can");
        }
        if (!sheet.allows("open", roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not open a claim");
        }
        BigDecimal part = scaled(amount);
        if (part.signum() <= 0 || part.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The amount split off must be more than 0.00 and less than " + claim.getAmount().toPlainString());
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setAmount(claim.getAmount().subtract(part));
        claim.unbind();
        Claim original = claims.saveAndFlush(claim);
        LocalDate date = original.getDate();
        LocalDate dueBy = holidays.dueBy(sheetId, date, termsOf(sheet, person, date));
        Claim added = new Claim(sheetId, person.getId(), part, date, dueBy, original.getNote(), null,
                claims.lastListed(sheetId) + 1);
        added.openAsSplit(nextNumber(sheet, dueBy), user, original.getNumber());
        if (original.isLateEntry()) {
            added.markLateEntry();
        }
        added.logStatus(user, sheet.getToday(), shownDue(sheet, added, person));
        added = claims.saveAndFlush(added);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_SPLIT", user, person, original, original.getNumber())
                + " into=" + added.getNumber());
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_OPENED", user, person, added, added.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return new Split(original, added);
    }

    /** The PENDING claim a steward approves or rejects; refused for a clerk, a claim in any other status and one
     *  due by inside a closed period. */
    private Claim pendingClaim(String sheetId, String user, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for approval");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim");
        }
        return claim;
    }

    /** The due a claim would be settled at now: its bound figure, else its live one, with the late charge it has
     *  gained on top, raised to the floor in force on its date; null when it has no figure. */
    private BigDecimal dueNow(Sheet sheet, Claim claim, Person person) {
        BigDecimal live = liveNow(sheet, claim, person);
        // Settling freezes the claim's figure (its bound one, else its live one) with the late charge it has gained,
        // raised to the floor.
        BigDecimal figure = claim.isBound() ? claim.getBoundDue() : live;
        return figure == null ? null : floored(claim, figure.add(lateCharge(claim, figure, sheet, person.gradeOn(claim.getDate()))), sheet);
    }

    /** A claim's live figure as rates and grades are now, bound by no statement; null when its grade has
     *  no rate in force on its date. */
    private BigDecimal liveNow(Sheet sheet, Claim claim, Person person) {
        String sheetId = sheet.getId();
        return Rate.inForceOn(rates.findBySheetIdAndGrade(sheetId, person.gradeOn(claim.getDate())), claim.getDate())
                .map(Rate::getRate)
                .map(rate -> liveFigure(claim, rate, sheet.roundingFor(roundingDate(claim,
                        claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, claim.getPersonId())))))
                .orElse(null);
    }

    /** The due a claim shows as it is now: the due fixed when it was settled, else the one it would be settled at. */
    private BigDecimal shownDue(Sheet sheet, Claim claim, Person person) {
        return claim.getSettledDue() != null ? claim.getSettledDue() : dueNow(sheet, claim, person);
    }

    private static String dueText(Claim claim) {
        return claim.getSettledDue() == null ? "n/a" : claim.getSettledDue().toPlainString();
    }

    /** The next number for a claim due by this day, opened now: within its period (<period name>-<n>, or X-<n>
     *  in no period) while the sheet's period numbering is on by its today, else the sheet's next number. */
    private String nextNumber(Sheet sheet, LocalDate date) {
        if (!sheet.numbersPerPeriod()) {
            return String.valueOf(sheet.nextClaimNumber());
        }
        String prefix = prefixOn(sheet, date);
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(sheet.getId(), prefix)
                .orElseGet(() -> new ClaimSequence(sheet.getId(), prefix));
        String number = sequence.next();
        sequences.save(sequence);
        return number;
    }

    /** The prefix of the numbers given within the period a day is in: its name, or X in no period. */
    private String prefixOn(Sheet sheet, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(sheet.getId()).stream()
                .filter(p -> p.contains(date)).map(Period::getName).findFirst().orElse(NO_PERIOD);
    }

    /** Refuse to settle or approve a claim with no proofs whose due is above the proof threshold in force on its
     *  date (a claim with no due, or on a sheet with no threshold then, needs none). */
    private static void refuseUnproven(Sheet sheet, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheet.proofThresholdOn(claim.getDate());
        if (claim.getProofs() == 0 && threshold != null && due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim whose due is above the proof threshold of " + threshold + " needs a proof first");
        }
    }

    /** Refuse to open a claim with an empty note whose due is above the note threshold in force on its date (a claim
     *  with no due, or on a sheet with no threshold then, needs none). */
    private static void refuseUnnoted(Sheet sheet, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheet.noteThresholdOn(claim.getDate());
        if ((claim.getNote() == null || claim.getNote().isBlank()) && threshold != null && due != null
                && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim whose due is above the note threshold of " + threshold + " needs a note first");
        }
    }

    /** Whether a settle by this user must wait for a steward: they are a clerk and the due is above the sheet's
     *  clerk limit (a claim with no due is within any limit). */
    private boolean overClerkLimit(Sheet sheet, String user, BigDecimal due) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return !Role.STEWARD.equals(roles.roleOf(sheet.getId(), user));
    }

    /** Refuse to void a SETTLED claim for anyone but a steward, and on a sheet's today more than
     *  {@value #SETTLED_VOID_DAYS} days after (or before) the day its log records it settled. */
    private void refuseSettledVoid(Sheet sheet, String user, Claim claim) {
        if (!Role.STEWARD.equals(roles.roleOf(sheet.getId(), user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may void a SETTLED claim");
        }
        LocalDate settled = claim.settledOn();
        LocalDate today = sheet.getToday();
        if (settled == null || today.isBefore(settled) || today.isAfter(settled.plusDays(SETTLED_VOID_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SETTLED claim can be voided only within " + SETTLED_VOID_DAYS + " days of its settlement");
        }
    }

    /** Refuse a correction (a negative amount) that does not name, by number, a SETTLED claim of the same person,
     *  and a claim that is not a correction but names one. */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only a correction (a negative amount) names a claim it corrects");
            }
            return;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, personId).stream()
                .anyMatch(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction must name the number of a SETTLED claim of the same person");
        }
    }

    /** Refuse a change to a claim whose due-by is inside a closed period of the sheet. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's due-by is inside a closed period");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.refusesClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That claim is dated after " + person.getName() + " retired");
        }
    }

    /** An audit line about a claim; one with a number (a void claim keeps the one it had) ends with it. */
    private static String entry(String event, String user, Person person, Claim claim, String number) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number);
    }

    /** The due on an amount at a rate (a percent): amount × rate / 100, rounded to the cent as the claim it is on
     *  rounds (half up rounds a negative half away from zero, as its opposite would). */
    public static BigDecimal dueOn(BigDecimal amount, BigDecimal rate, RoundingMode rounding) {
        return amount.multiply(rate).movePointLeft(2).setScale(2, rounding);
    }

    /** The date that decides how a claim's live figures round: its own date, or for a correction the date of the
     *  claim of the same person it corrects. */
    public static LocalDate roundingDate(Claim claim, Collection<Claim> sheetClaims) {
        if (claim.getCorrects() == null) {
            return claim.getDate();
        }
        return sheetClaims.stream()
                .filter(c -> c.getPersonId().equals(claim.getPersonId()) && claim.getCorrects().equals(c.getNumber()))
                .map(Claim::getDate).findFirst().orElse(claim.getDate());
    }

    /** The day a claim's overdue days are counted from: its due-by plus the grace days, in force on its date, of
     *  {@code grade} (its person's grade in force on its date). Its due-by itself is unchanged. */
    public static LocalDate overdueFrom(Claim claim, Sheet sheet, String grade) {
        return claim.getDueBy().plusDays(sheet.graceFor(grade, claim.getDate()));
    }

    /** Whether a claim whose person is of {@code grade} on its date is overdue on the sheet's today: OPEN or PENDING,
     *  and its due-by plus its grade's grace days is before today. */
    public static boolean isOverdue(Claim claim, Sheet sheet, String grade) {
        return claim.getStatus().isOpen() && overdueFrom(claim, sheet, grade).isBefore(sheet.getToday());
    }

    /** The late charge an OPEN or PENDING claim, not deferred, has gained on its figure by the sheet's today: for each
     *  full 30 days past its due-by plus its grade's grace days ({@code grade}: its person's grade in force on its
     *  date), the late percent in force on that boundary date (that day plus 30, 60, 90 ... days) of the figure,
     *  rounded as the claim rounds, added up; 0.00 when not overdue, it has no figure, or it is a correction. */
    public static BigDecimal lateCharge(Claim claim, BigDecimal figure, Sheet sheet, String grade) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!claim.getStatus().isOpen() || claim.isDeferredOn(sheet.getToday())) {
            return none;
        }
        return lateChargeAsOpen(claim, figure, sheet, grade);
    }

    /** The late charge a claim would have gained on its figure by the sheet's today were it still open. */
    private static BigDecimal lateChargeAsOpen(Claim claim, BigDecimal figure, Sheet sheet, String grade) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (figure == null || claim.isCorrection()) {
            return none;
        }
        LocalDate from = overdueFrom(claim, sheet, grade);
        long periods = ChronoUnit.DAYS.between(from, sheet.getToday()) / 30;
        RoundingMode rounding = sheet.roundingFor(claim.getDate());
        BigDecimal charge = none;
        for (long k = 1; k <= periods; k++) {
            charge = charge.add(dueOn(figure, sheet.latePctOn(from.plusDays(30 * k)), rounding));
        }
        return charge;
    }

    /** A claim's live figure at a rate: its due on its amount, rounded as the claim rounds. The floor is not applied
     *  to it: it is applied last, to the figure with its late charge (see {@link #floored(Claim, BigDecimal, Sheet)}). */
    public static BigDecimal liveFigure(Claim claim, BigDecimal rate, RoundingMode rounding) {
        return dueOn(claim.getAmount(), rate, rounding);
    }

    /** A claim's due (its figure with its late charge) raised to the floor in force on its date, unless it is a
     *  correction. */
    public static BigDecimal floored(Claim claim, BigDecimal due, Sheet sheet) {
        return due == null || claim.isCorrection() || sheet == null ? due : floored(due, sheet.floorOn(claim.getDate()));
    }

    /** A live due raised to the sheet's floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor : due;
    }

    /** The terms in days a claim of the person dated {@code date} is due by: the person's own, else their grade's
     *  in force then, else the sheet's then. */
    private static int termsOf(Sheet sheet, Person person, LocalDate date) {
        return person.getTerms() != null ? person.getTerms() : sheet.termsFor(person.gradeOn(date), date);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** An external reference as kept: trimmed, none when blank. */
    public static String refOf(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    /** The refusal of an external reference already used on the sheet. */
    public static ResponseStatusException refUsed(String ref) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The reference " + ref + " is already used on this sheet");
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
