package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import net.officefloor.hq.app.watch.WatchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
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
    private final RateChains rates;
    private final Audit audit;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final ClaimSequenceRepository sequences;
    private final ClaimFiscalSequenceRepository fiscalSequences;
    private final WatchService watches;
    private final HolidayService holidays;
    private final VoidReasonService voidReasons;
    private final SheetHistory history;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, RateChains rates, Audit audit, Rollup rollup, PeriodRepository periods,
            RoleService roles, ClaimSequenceRepository sequences, ClaimFiscalSequenceRepository fiscalSequences,
            WatchService watches, HolidayService holidays, VoidReasonService voidReasons, SheetHistory history) {
        this.fiscalSequences = fiscalSequences;
        this.history = history;
        this.voidReasons = voidReasons;
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

    /** The claim's timeline: the sheet's audit lines about it, newest first (see {@link Audit#claimLines}). */
    @Transactional(readOnly = true)
    public List<String> timeline(String sheetId, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        return audit.claimLines(sheetId, claim.getNumber(), person.getName(), claim.getAmount().toPlainString(),
                claim.getDate().toString());
    }

    /** The ids of those given that are claims on the sheet, in list order: people in the order they are listed,
     *  each person's claims newest first. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        Map<Long, Integer> place = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            place.put(listed.get(i).getId(), i);
        }
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparing(c -> place.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
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
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /** Add a claim as {@link #add(String, String, long, BigDecimal, LocalDate, String, String, String)} does, with
     *  its tags (comma separated; each trimmed and lower-cased, blanks and repeats dropped). */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        LocalDate today = sheet.getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate claimDate = date != null ? date : sheet.defaultClaimDate();
        int terms = termsOf(sheet, person);
        LocalDate dueBy = holidays.dueBy(sheetId, claimDate, terms);
        refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, claimDate);
        refuseUnlessCorrects(sheetId, personId, amount, corrects, null);
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCase(sheetId, reference)) {
            throw refUsed(reference);
        }
        Rollup.Figures before = rollup.of(sheetId);
        Claim claim = new Claim(sheetId, personId, scaled(amount), claimDate, dueBy, noteOf(note), corrects,
                claims.lastListed(sheetId) + 1);
        claim.setTerms(terms);
        boolean late = periods.existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                sheetId, dueBy, dueBy);
        if (late) {
            claim.markLateEntry();
        }
        claim.setRef(reference);
        String tagged = tagsOf(tags);
        claim.setTags(tagged);
        Claim added = claims.saveAndFlush(claim);
        added.logStatus(user, today, shownDue(sheet, added, person));
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_ADDED", user, person, added, added.getNumber())
                + (reference == null ? "" : " ref=" + reference) + (tagged == null ? "" : " tags=" + tagged));
        if (late) {
            audit.recordChangeOnCommit(sheetId, user, entry("LATE_ENTRY", user, person, added, null));
        }
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** Whether the claim form would take a claim for the person dated {@code date} that corrects none and has no
     *  external reference: its due-by is not inside a closed period, and it is not dated after the person retired. */
    @Transactional(readOnly = true)
    public boolean accepts(String sheetId, long personId, LocalDate date) {
        Sheet sheet = sheetStore.findById(sheetId).orElseThrow();
        Person person = people.findByIdAndSheetId(personId, sheetId).orElse(null);
        if (person == null || person.refusesClaimOn(date)) {
            return false;
        }
        LocalDate dueBy = holidays.dueBy(sheetId, date, termsOf(sheet, person));
        return !periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, dueBy, dueBy);
    }

    /** Change a claim's amount, date, note and the claim it corrects; a missing date takes the sheet's today. Only
     *  a draft can be changed: to change an open claim, void it and add a new one. Its due-by stays as it was fixed.
     *  Refused when its due-by is inside a closed period, or when it would be a correction that does not name a
     *  SETTLED claim of its person, when its external reference is already used by another claim on the sheet, or
     *  while another user has its editor open, or when it has lines and another amount is given. Saving releases its lock and records this user as its last editor. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
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
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects, claim.getId());
        if (!claim.getLines().isEmpty() && scaled(amount).compareTo(claim.getAmount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with lines has the amount of its lines; it cannot be given another");
        }
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCaseAndIdNot(sheetId, reference, claim.getId())) {
            throw refUsed(reference);
        }
        Rollup.Figures before = rollup.of(sheetId);
        claim.setAmount(scaled(amount));
        claim.setRef(reference);
        claim.setTags(tagsOf(tags));
        claim.setCorrects(corrects);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claim.setLockedBy(null);
        claim.setEditedBy(user);
        Claim edited = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(edited.getPersonId(), sheetId).orElseThrow();
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_EDITED", user, person, edited, edited.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /**
     * Move a claim to a new status; a move its current status does not allow is refused. Opening a
     * claim records who opened it, giving it no number; settling it gives it its next number (the sheet's next, or
     * the next in its period while the sheet numbers per period) unless it has one; voiding it keeps its number, which is
     * never given again; settling it fixes its due: the figure a statement binds it to, else its amount
     * at the rate in force on its date of its person's grade in force on that date, as rates are now, with the late charge it has gained
     * added on top, so later rate changes leave it as it is. The user
     * who opened a claim, or last edited it, cannot settle it, and a clerk settling it when that due is above the sheet's clerk limit
     * makes it PENDING instead, for a steward to approve or reject (a claim with no due is within any limit); a
     * steward settles anything. Opening, settling and voiding are each refused for a role the sheet's permission
     * matrix does not allow. Opening a claim whose due is above the review threshold in force on its date makes it
     * REVIEW instead, with no number yet, recording who opened it. Only a steward may void a SETTLED claim, and only within {@value #SETTLED_VOID_DAYS}
     * days of the day its log records it settled, by the sheet's today; its frozen figure then leaves every total
     * and the audit line ends with it as reversed. A claim due by inside a closed period cannot move. The sheet is locked
     * first, so moves on one sheet are made one at a time and no number is given twice.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        return changeStatus(sheetId, user, id, next, null);
    }

    /** Move a claim to a new status, as {@link #changeStatus(String, String, long, ClaimStatus)}; a void gives
     *  {@code reason} (none when null or blank). While the sheet's list of void reasons is not empty, a void needs a
     *  reason on it; the claim keeps the reason and the audit line ends with it. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason) {
        return changeStatus(sheetId, user, id, next, reason, true, null);
    }

    /** Move a claim to a new status, as {@link #changeStatus(String, String, long, ClaimStatus, String)}; a settle
     *  as of a date ({@code on}, none when null) fixes the claim's due as it stood at the end of that date (see
     *  {@link #dueAsOf}) and the CLAIM_SETTLED line ends with that date. Refused for a date after the sheet's today
     *  and for a claim not on the sheet by then. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason, LocalDate on) {
        return changeStatus(sheetId, user, id, next, reason, true, on);
    }

    /** Move a claim to a new status, as {@link #changeStatus(String, String, long, ClaimStatus, String)}; while
     *  {@code judged} is false the four-eyes rules and the proof rule are left to the caller, who has judged them on
     *  another claim. */
    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason, boolean judged) {
        return changeStatus(sheetId, user, id, next, reason, judged, null);
    }

    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason, boolean judged,
            LocalDate on) {
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
        if (next == ClaimStatus.DRAFT && !sheet.getToday().equals(claim.deletedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A deleted claim can be restored only on the day it was deleted");
        }
        boolean voidsSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidsSettled) {
            refuseSettledVoid(sheet, user, claim);
        }
        String voidReason = next == ClaimStatus.VOID ? voidReasons.refuseUnlisted(sheetId, reason) : null;
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheet.getToday())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (judged && next == ClaimStatus.SETTLED && claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened or last edited a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        LocalDate settledOn = next == ClaimStatus.SETTLED ? on : null;
        BigDecimal due = settledOn != null ? dueAsOf(sheet, claim, settledOn) : dueNow(sheet, claim, person);
        if (next == ClaimStatus.SETTLED) {
            if (judged) {
                refuseUnproven(sheet, claim, person, due);
            }
        } else if (next == ClaimStatus.OPEN) {
            refuseUnnoted(sheet, claim, due);
            refuseOverCap(sheetId, claim);
            refuseOverMaxOpen(sheet, claim);
            refuseFlagged(sheet, user, person);
        }
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheet, user, person, due)) {
            next = ClaimStatus.PENDING;
        }
        if (next == ClaimStatus.OPEN && overReviewThreshold(sheet, claim, due)) {
            next = ClaimStatus.REVIEW;
        }
        Rollup.Figures before = rollup.of(sheetId);
        String number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.REVIEW) {
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
            number = numberAtSettlement(sheet, claim);
        } else if (next == ClaimStatus.VOID) {
            claim.setVoidReason(voidReason);
        }
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DELETED -> "CLAIM_DELETED";
            case DRAFT -> "CLAIM_RESTORED";
            case REVIEW -> "CLAIM_REVIEW";
        };
        String line = entry(event, user, person, changed, number);
        if (next == ClaimStatus.SETTLED) {
            line += " due=" + dueText(changed);
            if (settledOn != null) {
                line += " on=" + settledOn;
            }
        } else if (voidsSettled) {
            line += " reversed=" + dueText(changed);
        }
        if (voidReason != null) {
            line += " reason=" + voidReason;
        }
        audit.recordChangeOnCommit(sheetId, user, line);
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** Settle every claim given, one at a time in the order given, by exactly the rules of settling one claim (a
     *  claim a clerk's limit makes PENDING counts as settled), as the sheet's next settle batch, each keeping its
     *  number; after the claims' own lines BATCH_SETTLED is recorded with the batch's count and the frozen figures
     *  of those SETTLED added up. All or nothing: when any claim cannot be settled, none is, nothing is written and
     *  null is returned. The claims, as saved, in the order given; none when none is given. */
    @Transactional
    public List<Claim> settleBatch(String sheetId, String user, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        for (long id : ids) {
            try {
                changeStatus(sheetId, user, id, ClaimStatus.SETTLED);
            } catch (ResponseStatusException refusal) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return null;
            }
        }
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        int batch = sheet.nextSettleBatch();
        sheetStore.saveAndFlush(sheet);
        List<Claim> marked = ids.stream().map(id -> {
            Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow();
            claim.setSettleBatch(batch);
            return claims.saveAndFlush(claim);
        }).toList();
        BigDecimal total = marked.stream()
                .filter(claim -> claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null)
                .map(Claim::getSettledDue)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        audit.recordChangeOnCommit(sheetId, user, "BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch
                + " count=" + marked.size() + " total=" + total.toPlainString());
        return marked;
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

    /** Raise a claim's proofs count by one, of a kind (receipt or photo; null: a receipt, not chosen). Refused for
     *  a SETTLED or VOID claim and one due by inside a closed period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long id, String kind) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a proof");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.addProof(kind);
        Claim proven = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_PROOF_ADDED", user, person, proven, proven.getNumber())
                + " proofs=" + proven.getProofs() + (kind == null ? "" : " kind=" + kind));
        return proven;
    }

    /** Add a line to a DRAFT claim: a label (trimmed, not blank), a quantity (at least 1) and a unit amount (two
     *  decimals, not negative); its amount becomes its lines' totals added up. Adding a line edits the claim: this
     *  user becomes its last editor. Refused for a claim that is not a DRAFT, one due by inside a closed period and
     *  one another user has locked. */
    @Transactional
    public Claim addLine(String sheetId, String user, long id, String label, int qty, BigDecimal unit) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a line; only a DRAFT can");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfLockedByOther(claim, user);
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A line needs a label");
        }
        if (qty < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A line's quantity is at least 1");
        }
        if (unit.signum() < 0 || unit.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A line's unit amount has two decimals");
        }
        BigDecimal unitAmount = unit.setScale(2, RoundingMode.UNNECESSARY);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.addLine(trimmed, qty, unitAmount);
        claim.setEditedBy(user);
        Claim lined = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_LINE_ADDED", user, person, lined, lined.getNumber())
                + " label=" + trimmed + " qty=" + qty + " unit=" + unitAmount.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return lined;
    }

    /** Comment on a claim, as this user on the sheet's today. Refused for an empty comment. */
    @Transactional
    public Claim comment(String sheetId, String user, long id, String text) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment cannot be empty");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.comment(user, sheet.getToday(), trimmed);
        Claim commented = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("COMMENT_ADDED", user, person, commented,
                commented.getNumber()) + " text=" + trimmed);
        return commented;
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
        LocalDate previous = claim.getRemindedOn();
        boolean escalates = !claim.isEscalated() && sheet.getEscalateAfter() != null && previous != null
                && !previous.plusDays(sheet.getEscalateAfter()).isAfter(sheet.getToday());
        claim.remind(sheet.getToday());
        if (escalates) {
            claim.escalate();
        }
        Claim reminded = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("REMIND", user, person, reminded, reminded.getNumber()));
        if (escalates) {
            audit.recordChangeOnCommit(sheetId, user, entry("ESCALATE", user, person, reminded, reminded.getNumber()));
        }
        return reminded;
    }

    /** Hold an OPEN or PENDING claim, or release it when held: while held it counts in no live due figure
     *  unless a statement binds it, as a held person's claims do, though it still counts in what is claimed. Refused for a claim in any other status. */
    @Transactional
    public Claim toggleHold(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be held or released; only an OPEN or PENDING one can");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setHeld(!claim.isHeld());
        Claim saved = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user,
                entry(saved.isHeld() ? "CLAIM_HELD" : "CLAIM_RELEASED", user, person, saved, saved.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward waives an OPEN or PENDING claim's late charge: it bears none from then on, whatever its overdue
     *  state. Refused for anyone but a steward, a claim in any other status and a claim already waived. */
    @Transactional
    public Claim waive(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward waives a claim's late charge");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be waived; only an OPEN or PENDING one can");
        }
        if (claim.isWaived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim's late charge was already waived");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.waive();
        Claim waived = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_WAIVED", user, person, waived, waived.getNumber()));
        return waived;
    }

    /** A steward assigns a REVIEW claim to a user to review; REVIEW_ASSIGNED names the claim and ends with the user.
     *  Refused for anyone but a steward, a blank user and a claim in any other status. */
    @Transactional
    public Claim assignReview(String sheetId, String user, long id, String assignee) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward assigns a claim's review");
        }
        String to = assignee == null ? "" : assignee.trim();
        if (to.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Name the user to review the claim");
        }
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for review");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        claim.setReviewAssigned(to);
        Claim assigned = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("REVIEW_ASSIGNED", user, person, assigned, null)
                + " user=" + to);
        return assigned;
    }

    /** A steward approves a PENDING claim: it becomes SETTLED, taking its next number as settling does, its due fixed as it is at this moment (its bound figure, else its live
     *  one, with the late charge it has gained). Refused for a clerk, for the user who opened the claim or last edited it, for a
     *  claim that is not PENDING and for one due by inside a closed period. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = pendingClaim(sheetId, user, id);
        if (claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened or last edited a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = dueNow(sheet, claim, person);
        refuseUnproven(sheet, claim, person, due);
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        numberAtSettlement(sheet, claim);
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_APPROVED", user, person, changed, changed.getNumber())
                + " due=" + dueText(changed));
        watches.notifyWatchers(sheetId, user, person, changed.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward reviews a REVIEW claim: it becomes OPEN, with no number yet as opening it gives none, and keeps who
     *  opened it. CLAIM_REVIEWED is recorded, then CLAIM_OPENED. Refused for anyone but a steward,
     *  for a claim that is not REVIEW, for one due by inside a closed period, and for one whose period's cap its
     *  person already has that many open claims in. */
    @Transactional
    public Claim review(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for review");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward reviews a claim");
        }
        refuseOverCap(sheetId, claim);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claim.logStatus(user, sheet.getToday(), shownDue(sheet, claim, person));
        Claim changed = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_REVIEWED", user, person, changed, null));
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_OPENED", user, person, changed, changed.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, still bound by its person's last
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
        BigDecimal due = live == null ? null : live.add(lateChargeAsOpen(claim, live, sheet, person.gradeOn(claim.getDate())));
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

    /** Share a claim with another person on the sheet: they hold a part of its due, their share percent (two
     *  decimals, more than 0.00) of it. Refused for the claim's own person, a person already holding a share of it,
     *  shares adding up to more than 100.00, and a SETTLED, VOID or DELETED claim. */
    @Transactional
    public Claim share(String sheetId, String user, long id, long withPersonId, BigDecimal share) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        Person with = people.findByIdAndSheetId(withPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be shared");
        }
        if (claim.getPersonId().equals(with.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A claim cannot be shared with its own person");
        }
        if (claim.shareOf(with.getId()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, with.getName() + " already holds a share of that claim");
        }
        if (share.signum() <= 0 || share.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A share has two decimals and is more than 0.00");
        }
        BigDecimal pct = share.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal total = claim.getShares().stream().map(ClaimShare::getShare).reduce(pct, BigDecimal::add);
        if (total.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A claim's shares add up to at most 100.00");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.share(with.getId(), pct);
        Claim shared = claims.saveAndFlush(claim);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_SHARED", user, person, shared, shared.getNumber())
                + " with=" + with.getName() + " share=" + pct.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return shared;
    }

    /** A user whose role may reassign moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its date,
     *  takes the next number in the sequence it is numbered in (the sheet's, or its period's; a DRAFT, having no
     *  number, gets none) with its old number never used again, loses any figure a statement bound it to (it is live again until its new person's next statement), and is
     *  listed as its new person's newest claim. Refused for a role the permission matrix does not allow (by default a clerk), for a SETTLED or VOID claim, for a move to the
     *  person it is already of, for one due by inside a closed period or after the new person retired, and for a
     *  correction (other than a DRAFT) the new person has no SETTLED claim of that number for. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        return reassign(sheetId, user, id, toPersonId, false);
    }

    /** Move a claim as {@link #reassign(String, String, long, long)} does, except that a bulk move also takes a
     *  claim already of the person it is moved to (it is listed again as that person's newest). */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId, boolean bulk) {
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
        if (!bulk && claim.getPersonId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is already " + to.getName() + "'s");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfRetired(to, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            refuseUnlessCorrects(sheetId, to.getId(), claim.getAmount(), claim.getCorrects(), claim.getId());
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.unbind();
        if (claim.getStatus() != ClaimStatus.DRAFT && claim.getNumber() != null) {
            claim.setNumber(nextNumber(sheet, claim.getDueBy()));
        }
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
     *  and note, no number (it takes one when settled), opened by this user, a due-by fixed now from the terms in force on the sheet's today and
     *  naming the original as the claim it was split from, is listed as the person's newest; it is a late entry when
     *  the original is, and is numbered within the period of its own due-by. Refused for a claim
     *  that is not OPEN or is deferred, one with lines, one due by inside a closed period, an amount out of range, and a role the
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
        if (!claim.getLines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with lines has the amount of its lines; it cannot be split");
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
        int terms = termsOf(sheet, person);
        LocalDate dueBy = holidays.dueBy(sheetId, date, terms);
        Claim added = new Claim(sheetId, person.getId(), part, date, dueBy, original.getNote(), null,
                claims.lastListed(sheetId) + 1);
        added.setTerms(terms);
        added.openAsSplit(null, user, original.getNumber());
        added.setTags(original.getTags());
        if (original.isLateEntry()) {
            added.markLateEntry();
        }
        added.logStatus(user, sheet.getToday(), shownDue(sheet, added, person));
        added = claims.saveAndFlush(added);
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_SPLIT", user, person, original, original.getNumber())
                + " into=" + (added.getNumber() == null ? "" : added.getNumber()));
        audit.recordChangeOnCommit(sheetId, user, entry("CLAIM_OPENED", user, person, added, added.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return new Split(original, added);
    }

    /** Part-settle an OPEN claim: split the amount off it exactly as {@link #split} does, then settle the new claim
     *  exactly as {@link #changeStatus(String, String, long, ClaimStatus)} does (PENDING when the clerk limit says
     *  so), save that the four-eyes rules and the proof rule are judged on the original claim as it was before the
     *  split. A split or settle that would be refused refuses the whole action and nothing is written. The original,
     *  keeping the rest of its amount, and the new claim, as saved. */
    @Transactional
    public Split settlePart(String sheetId, String user, long id, BigDecimal amount) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be part-settled; only an OPEN one can");
        }
        if (claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened or last edited a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        refuseUnproven(sheet, claim, person, dueNow(sheet, claim, person));
        Split split = split(sheetId, user, id, amount);
        return new Split(split.original(),
                changeStatus(sheetId, user, split.split().getId(), ClaimStatus.SETTLED, null, false));
    }

    /** The PENDING claim a steward, or its person's approver, approves or rejects; refused for anyone else, a claim
     *  in any other status and one due by inside a closed period. */
    private Claim pendingClaim(String sheetId, String user, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for approval");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user)) && !people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .map(p -> p.isApprover(user)).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward or the person's approver approves or rejects a claim");
        }
        return claim;
    }

    /** The due a claim would be settled at now: its bound figure, else its live one (raised to the floor in force on
     *  its date), with the late charge it has gained on that figure on top; null when it has no figure. */
    private BigDecimal dueNow(Sheet sheet, Claim claim, Person person) {
        BigDecimal live = liveNow(sheet, claim, person);
        // Settling freezes the claim's figure (its bound one, else its floored live one) with the late charge it has
        // gained on it.
        BigDecimal figure = claim.isBound() ? claim.getBoundDue() : live;
        return figure == null ? null : figure.add(lateCharge(claim, figure, sheet, person.gradeOn(claim.getDate())));
    }

    /** The due a claim would have been settled at as it stood at the end of a date, that date standing in for the
     *  sheet's today, as the as-of view works it out: the sheet, its rates, the claim's person and its claims as they
     *  stood then (see {@link SheetHistory}), its bound figure, else its live one raised to the floor in force then,
     *  with the late charge it had gained on that figure by then on top; null when it had no figure. Refused for a date after the
     *  sheet's today and for a claim not on the sheet by then. */
    private BigDecimal dueAsOf(Sheet sheet, Claim claim, LocalDate on) {
        String sheetId = sheet.getId();
        if (on.isAfter(sheet.getToday())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A claim can be settled as of a date on or before " + sheet.getToday());
        }
        SheetState then = history.asOf(sheetId, on).orElse(null);
        Claim claimThen = then == null ? null : then.rebuildClaims(sheetId).stream()
                .filter(c -> c.getId().equals(claim.getId())).findFirst().orElse(null);
        SheetState.PersonState personState = claimThen == null ? null
                : then.person(claimThen.getPersonId()).orElse(null);
        if (personState == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim was not on the sheet by " + on);
        }
        Sheet sheetThen = then.rebuildSheet(sheet, on);
        Person personThen = personState.rebuild(sheetId);
        List<Claim> claimsThen = then.rebuildClaims(sheetId);
        if (claimThen.getSettledDue() != null) {
            return claimThen.getSettledDue();
        }
        String grade = personThen.gradeOn(claimThen.getDate());
        BigDecimal figure = claimThen.isBound() ? claimThen.getBoundDue()
                : history.ratesOf(sheetId, then, on).inForceOn(grade, claimThen.getDate(), claimThen.getId())
                        .map(Rate::getRate)
                        .map(rate -> floored(claimThen, liveFigure(claimThen, rate,
                                sheetThen.roundingFor(roundingDate(claimThen, claimsThen))), sheetThen))
                        .orElse(null);
        return figure == null ? null : figure.add(lateCharge(claimThen, figure, sheetThen, grade));
    }

    /** A claim's live figure as rates and grades are now, bound by no statement, raised to the floor in force on its
     *  date; null when its grade has
     *  no rate in force on its date. */
    private BigDecimal liveNow(Sheet sheet, Claim claim, Person person) {
        String sheetId = sheet.getId();
        return rates.of(sheetId).inForceOn(person.gradeOn(claim.getDate()), claim.getDate(), claim.getId())
                .map(Rate::getRate)
                .map(rate -> floored(claim, liveFigure(claim, rate, sheet.roundingFor(roundingDate(claim,
                        claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, claim.getPersonId())))), sheet))
                .orElse(null);
    }

    /** The due a claim shows as it is now: the due fixed when it was settled, else the one it would be settled at. */
    private BigDecimal shownDue(Sheet sheet, Claim claim, Person person) {
        return claim.getSettledDue() != null ? claim.getSettledDue() : dueNow(sheet, claim, person);
    }

    private static String dueText(Claim claim) {
        return claim.getSettledDue() == null ? "n/a" : claim.getSettledDue().toPlainString();
    }

    /** Give a claim being settled (or approved) its number, the next in its sequence by its due-by as the sheet
     *  stands now, unless it already has one (a claim numbered before numbers were given at settlement keeps it).
     *  The claim's number. */
    private String numberAtSettlement(Sheet sheet, Claim claim) {
        if (claim.getNumber() == null) {
            claim.setNumber(nextNumber(sheet, claim.getDueBy()));
        }
        return claim.getNumber();
    }

    /** The next number on a sheet, locked by the caller, for a claim due by this day arriving there now, as
     *  {@link #nextNumber(Sheet, LocalDate)} gives it. */
    public String nextNumberOn(Sheet sheet, LocalDate dueBy) {
        return nextNumber(sheet, dueBy);
    }

    /** The next number for a claim due by this day, opened now: within its period (<period name>-<n>, or X-<n>
     *  in no period) while the sheet's period numbering is on by its today, else the next number in the sheet-wide
     *  sequence its tree shares within the fiscal year of the day (FY<year>-<n>, counting from 1 each year). */
    private String nextNumber(Sheet sheet, LocalDate date) {
        if (!sheet.numbersPerPeriod()) {
            int year = sheet.fiscalYearOf(date);
            int last = lastNumberInTree(sheet, year);
            ClaimFiscalSequence sequence = fiscalSequences.findBySheetIdAndFiscalYear(sheet.getId(), year)
                    .orElseGet(() -> new ClaimFiscalSequence(sheet.getId(), year));
            String number = sequence.nextAfter(last);
            fiscalSequences.save(sequence);
            return number;
        }
        String prefix = prefixOn(sheet, date);
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(sheet.getId(), prefix)
                .orElseGet(() -> new ClaimSequence(sheet.getId(), prefix));
        String number = sequence.next();
        sequences.save(sequence);
        return number;
    }

    /** The last sheet-wide number given in a fiscal year anywhere in the sheet's tree but the sheet itself: the
     *  sheet at the top of its line of parents and all of that sheet's descendants. The top sheet is locked so the
     *  tree's numbers are given one at a time. */
    private int lastNumberInTree(Sheet sheet, int fiscalYear) {
        List<String> ancestors = sheetStore.ancestorsOf(sheet.getId(), Integer.MAX_VALUE);
        String top = ancestors.isEmpty() ? sheet.getId() : ancestors.get(ancestors.size() - 1);
        if (!top.equals(sheet.getId())) {
            sheetStore.findLockedById(top);
        }
        Set<String> others = new HashSet<>(sheetStore.descendantsOf(top));
        others.add(top);
        others.remove(sheet.getId());
        return others.isEmpty() ? 0 : fiscalSequences.lastNumberOf(others, fiscalYear);
    }

    /** The prefix of the numbers given within the period a day is in: its name, or X in no period. */
    private String prefixOn(Sheet sheet, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(sheet.getId()).stream()
                .filter(p -> p.contains(date)).map(Period::getName).findFirst().orElse(NO_PERIOD);
    }

    /** Refuse to settle or approve a claim whose due is above the proof threshold in force on its date for its
     *  person's grade then while it has no proofs, or, while the sheet sets proof kinds, no proof of one of them (a
     *  claim with no due, or with no threshold then, needs none). */
    private static void refuseUnproven(Sheet sheet, Claim claim, Person person, BigDecimal due) {
        BigDecimal threshold = sheet.proofThresholdFor(person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold == null || due == null || due.compareTo(threshold) <= 0) {
            return;
        }
        if (claim.getProofs() == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim whose due is above the proof threshold of " + threshold + " needs a proof first");
        }
        for (String kind : sheet.proofKindList()) {
            if (claim.proofsOf(kind) == 0) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                        "A claim whose due is above the proof threshold of " + threshold + " needs a " + kind + " proof first");
            }
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

    /** Whether opening a claim makes it REVIEW: its due is above the review threshold in force on its date (a claim
     *  with no due, or on a sheet with no threshold then, goes straight to OPEN). */
    private static boolean overReviewThreshold(Sheet sheet, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheet.reviewThresholdOn(claim.getDate());
        return threshold != null && due != null && due.compareTo(threshold) > 0;
    }

    /** Whether a settle by this user must wait for a steward: they are a clerk and the due is above the clerk limit
     *  in force on the sheet's today for the person's grade then (its own, else the sheet's; a claim with no due is
     *  within any limit). */
    private boolean overClerkLimit(Sheet sheet, String user, Person person, BigDecimal due) {
        BigDecimal limit = sheet.clerkLimitFor(person.gradeOn(sheet.getToday()));
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
     *  or that exceeds what remains of it (its amount less the corrections already made against it, VOID and
     *  DELETED ones and the claim {@code self} itself left out), and a claim that is not a correction but names one. */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects, Long self) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only a correction (a negative amount) names a claim it corrects");
            }
            return;
        }
        List<Claim> own = corrects == null ? List.of()
                : claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, personId);
        Claim target = own.stream()
                .filter(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED)
                .findFirst().orElse(null);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction must name the number of a SETTLED claim of the same person");
        }
        BigDecimal remaining = own.stream()
                .filter(c -> corrects.equals(c.getCorrects()) && !c.getId().equals(self)
                        && c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .map(Claim::getAmount).reduce(target.getAmount(), BigDecimal::add);
        if (scaled(amount).negate().compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction may not exceed what remains of " + corrects + " (" + scaled(remaining).toPlainString() + ")");
        }
    }

    /** Refuse a change to a claim whose due-by is inside a closed period of the sheet. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's due-by is inside a closed period");
        }
    }

    /** Refuse opening a claim of a person flagged on the sheet's today for anyone but a steward. */
    private void refuseFlagged(Sheet sheet, String user, Person person) {
        if (person.flagOn(sheet.getToday()) != null && !Role.STEWARD.equals(roles.roleOf(sheet.getId(), user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward may open a claim of a flagged person");
        }
    }

    /** Refuse opening a claim whose due-by is inside a period with a cap its person already has that many OPEN or
     *  PENDING claims in. */
    private void refuseOverCap(String sheetId, Claim claim) {
        for (Period period : periods.findBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId,
                claim.getDueBy(), claim.getDueBy())) {
            if (period.getClaimCap() == null) {
                continue;
            }
            long held = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, claim.getPersonId()).stream()
                    .filter(c -> !c.getId().equals(claim.getId()))
                    .filter(c -> c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.PENDING)
                    .filter(c -> period.contains(c.getDueBy()))
                    .count();
            if (held >= period.getClaimCap()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This person already has " + held
                        + " open claims in period " + period.getName() + ", its cap");
            }
        }
    }

    /** Refuse opening a claim whose person already has the sheet's maximum of OPEN or PENDING claims. */
    private void refuseOverMaxOpen(Sheet sheet, Claim claim) {
        Integer max = sheet.getMaxOpen();
        if (max == null) {
            return;
        }
        long held = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheet.getId(), claim.getPersonId()).stream()
                .filter(c -> !c.getId().equals(claim.getId()))
                .filter(c -> c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.PENDING)
                .count();
        if (held >= max) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This person already has " + held + " open claims, the sheet's maximum");
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
     *  date), the late percent in force on that boundary date (that day plus 30, 60, 90 ... days) of the figure
     *  plus the charges of the boundaries before it (compounding), rounded as the claim rounds, added up; 0.00 when not overdue, it has no figure, it is a correction or its late charge was waived. While a
     *  statement binds the claim with a late charge, it is that charge, whatever the sheet's today. */
    public static BigDecimal lateCharge(Claim claim, BigDecimal figure, Sheet sheet, String grade) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!claim.getStatus().isOpen() || claim.isDeferredOn(sheet.getToday())) {
            return none;
        }
        // A statement binds the late charge with the figure: while bound, it no longer follows the sheet's today.
        if (claim.isBound() && claim.getBoundLate() != null && !claim.isCorrection() && !claim.isWaived()) {
            return claim.getBoundLate();
        }
        return lateChargeAsOpen(claim, figure, sheet, grade);
    }

    /** The late charge a claim would have gained on its figure by the sheet's today were it still open. */
    private static BigDecimal lateChargeAsOpen(Claim claim, BigDecimal figure, Sheet sheet, String grade) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (figure == null || claim.isCorrection() || claim.isWaived()) {
            return none;
        }
        LocalDate from = overdueFrom(claim, sheet, grade);
        long periods = ChronoUnit.DAYS.between(from, sheet.getToday()) / 30;
        RoundingMode rounding = sheet.roundingFor(claim.getDate());
        BigDecimal charge = none;
        for (long k = 1; k <= periods; k++) {
            charge = charge.add(dueOn(figure.add(charge), sheet.latePctOn(from.plusDays(30 * k)), rounding));
        }
        return charge;
    }

    /** A claim's figure at a rate: its due on its amount, rounded as the claim rounds. The floor is applied to it
     *  before the late charge is worked out (see {@link #floored(Claim, BigDecimal, Sheet)}). */
    public static BigDecimal liveFigure(Claim claim, BigDecimal rate, RoundingMode rounding) {
        return dueOn(claim.getAmount(), rate, rounding);
    }

    /** A claim's live figure (before its late charge) raised to the floor in force on its date, unless it is a
     *  correction. */
    public static BigDecimal floored(Claim claim, BigDecimal due, Sheet sheet) {
        return due == null || claim.isCorrection() || sheet == null ? due : floored(due, sheet.floorOn(claim.getDate()));
    }

    /** A live due raised to the sheet's floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor : due;
    }

    /** The terms in days a claim of the person created now is due by: those in force on the sheet's today, the day
     *  it is created (whatever its date): the person's own then, else those of the grade they have then, else the
     *  sheet's then. */
    private static int termsOf(Sheet sheet, Person person) {
        LocalDate today = sheet.getToday();
        Integer own = person.termsOn(today);
        return own != null ? own : sheet.termsFor(person.gradeOn(today), today);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** An external reference as kept: trimmed, none when blank. */
    public static String refOf(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    /** Tags as kept: each trimmed and lower-cased, blanks and repeats dropped, sorted and joined by commas; none
     *  when there are none. */
    public static String tagsOf(String tags) {
        if (tags == null) {
            return null;
        }
        String joined = String.join(",", new TreeSet<>(Arrays.stream(tags.split(","))
                .map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).toList()));
        return joined.isEmpty() ? null : joined;
    }

    /** The refusal of an external reference already used on the sheet. */
    public static ResponseStatusException refUsed(String ref) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The reference " + ref + " is already used on this sheet");
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
