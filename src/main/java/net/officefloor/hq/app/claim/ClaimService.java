package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonStatement;
import net.officefloor.hq.app.person.PersonStatementRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.person.StatementSummary;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    /** The period name a claim in no period is numbered under. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final ClaimLogRepository log;
    private final RetiredNumberRepository retired;
    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final PersonWatchRepository watches;
    private final RateRepository rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final SettingsService settings;
    private final HistoryService history;
    private final Audit audit;

    /** A statement just taken: the person, their statement summary now, and their claims, newest first. */
    public record Statement(Person person, StatementSummary summary, List<Claim> claims) {
    }

    /** A split just made: the original, keeping the rest of its amount, and the new claim. */
    public record Split(Claim original, Claim split) {
    }

    public ClaimService(ClaimRepository claims, ClaimLogRepository log, RetiredNumberRepository retired, PersonRepository people, PersonStatementRepository statements,
            PersonWatchRepository watches, RateRepository rates, SheetService sheets, RollupService rollup, PeriodService periods, SettingsService settings, HistoryService history, Audit audit) {
        this.claims = claims;
        this.log = log;
        this.retired = retired;
        this.people = people;
        this.statements = statements;
        this.watches = watches;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.settings = settings;
        this.history = history;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** The status logs of the sheet's claims, each oldest first, by claim id. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimLog>> logs(String sheetId) {
        Map<Long, List<ClaimLog>> byClaim = new HashMap<>();
        for (ClaimLog line : log.findBySheetIdOrderByIdAsc(sheetId)) {
            byClaim.computeIfAbsent(line.getClaimId(), k -> new ArrayList<>()).add(line);
        }
        return byClaim;
    }

    /** A claim's status log, oldest first. */
    @Transactional(readOnly = true)
    public List<ClaimLog> logOf(long claimId) {
        return log.findByClaimIdOrderByIdAsc(claimId);
    }

    /** The claims of the ids on the sheet in list order: the people in the order they were added,
     *  each person's claims newest first; ids not on the sheet are left out. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> theirs = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < theirs.size(); i++) {
            personOrder.put(theirs.get(i).getId(), i);
        }
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparingInt(c -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }

    /** Add a claim for a person on the sheet; a missing date takes the sheet's today. Refused
     *  (423) when the date is in a closed period, (422) when it is after the person's
     *  retirement date, and (422) when it is a correction (a negative amount) that does not name
     *  the number of a SETTLED claim of the same person. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, on);
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, personId, amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        int terms = person.getTerms() != null ? person.getTerms()
                : sheets.find(sheetId).map(s -> s.termsFor(person.gradeOn(on), on)).orElse(Sheet.DEFAULT_TERMS);
        Claim fresh = new Claim(sheetId, personId, amount, on, on.plusDays(terms), noteOf(note));
        fresh.setCorrects(corrected);
        // the sheet is locked (refuseIfClosed), so two claims added at once are listed one after the other
        fresh.setListed(claims.findLastListed(sheetId) + 1);
        fresh.setLateEntry(periods.softClosedOn(sheetId, on));
        Claim claim = claims.saveAndFlush(fresh);
        logStatus(user, person, claim);
        record("CLAIM_ADDED", user, person, claim);
        if (claim.isLateEntry()) {
            record("LATE_ENTRY", user, person, claim, null);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's today.
     *  Refused (409) once the claim is no longer a DRAFT (an open claim is voided and a new one
     *  added instead), and (423) when its old or new date is in a closed period, and (422) when the new
     *  date is after the person's retirement date, or when it is a correction that does not name
     *  the number of a SETTLED claim of the same person. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfLocked(claim, user);
        refuseIfClosed(sheetId, on);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, person.getId(), amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setNote(noteOf(note));
        claim.setLockedBy(null);
        claims.flush();
        record("CLAIM_EDITED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Open a DRAFT claim's editor for the user, locking the claim for everyone else until they
     *  save or cancel it or the sheet's today changes. Refused (404) when missing, (423) when
     *  dated in a closed period, (409) unless a DRAFT, and (409) while another user holds it. */
    @Transactional
    public Claim lock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfLocked(claim, user);
        claim.setLockedBy(user);
        claims.flush();
        return claim;
    }

    /** Close the user's editor of a claim without saving: their lock on it, if they hold it, is
     *  released. Refused (404) when missing. */
    @Transactional
    public Claim unlock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claims.flush();
        }
        return claim;
    }

    /** A change of the sheet's today releases every claim's lock on it. */
    @EventListener
    @Transactional
    public void unlockAll(TodayChanged change) {
        claims.unlockAll(change.sheetId());
    }

    /** Move a claim to a new status; a move its status does not allow is refused (409), as is
     *  a move the user's role may not make by the sheet's permission matrix (403), settling by the user who opened the claim (403), settling a deferred claim (409), settling a PENDING claim (409: it is
     *  approved or rejected instead), voiding a SETTLED claim by anyone but a steward (403) or more
     *  than 7 days after its settlement (409), and any move of a claim dated in a closed period (423). A
     *  clerk settling a claim whose due is above the sheet's clerk limit sends it to PENDING. Settling
     *  a claim whose due is above the proof threshold in force on its date while it has no proof is
     *  refused (422) before the clerk limit is considered. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        switch (to) {
            case OPEN -> settings.refuseUnlessAllowed(sheetId, user, Permission.OPEN);
            case SETTLED -> settings.refuseUnlessAllowed(sheetId, user, Permission.SETTLE);
            case VOID -> settings.refuseUnlessAllowed(sheetId, user, Permission.VOID);
            default -> {
            }
        }
        if (to == ClaimStatus.PENDING || (to == ClaimStatus.SETTLED && claim.getStatus() == ClaimStatus.PENDING)
                || !claim.getStatus().canMoveTo(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            refuseUnlessVoidableSettled(sheetId, user, claim);
        }
        if (to == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (to == ClaimStatus.OPEN) {
            refuseIfNoteless(claim, shownDueOf(claim, person));
        }
        // a claim bound by a statement settles at its bound figure, its late charge frozen in
        BigDecimal settling = to == ClaimStatus.SETTLED ? shownDueOf(claim, person) : null;
        if (to == ClaimStatus.SETTLED) {
            // before the clerk limit: a claim needing a proof never goes to PENDING
            refuseIfUnproven(claim, settling);
        }
        if (to == ClaimStatus.SETTLED && !settings.maySettle(sheetId, user, settling)) {
            // above the clerk limit: a steward approves or rejects it
            to = ClaimStatus.PENDING;
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        // a voided claim keeps its number (never reused), and the VOIDED record carries it
        String number = claim.getNumber();
        // a voided SETTLED claim keeps its frozen figure, which leaves every total
        boolean reversing = to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        BigDecimal reversed = claim.getSettledDue();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDate());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
        }
        claims.flush();
        logStatus(user, person, claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalArgumentException("A claim cannot return to DRAFT");
        };
        if (to == ClaimStatus.SETTLED) {
            record(event, user, person, claim, number, " due=" + dueText(claim.getSettledDue()));
            notifyWatchers(user, person, number);
        } else if (reversing) {
            record(event, user, person, claim, number, " reversed=" + dueText(reversed));
        } else {
            record(event, user, person, claim, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Refuse voiding a SETTLED claim (403) by anyone but a steward, and (409) once the sheet's
     *  today is more than {@link ClaimStatus#SETTLED_VOID_DAYS} days after its settlement (the
     *  first SETTLED line of its status log). */
    private void refuseUnlessVoidableSettled(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward voids a SETTLED claim");
        }
        LocalDate settledOn = log.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .filter(line -> line.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getOn)
                .findFirst()
                .orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today.isAfter(settledOn.plusDays(ClaimStatus.SETTLED_VOID_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SETTLED claim is voided only within " + ClaimStatus.SETTLED_VOID_DAYS + " days of its settlement");
        }
    }

    /** Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches
     *  it, the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     *  statement. Refused (404) when missing, (423) when dated in a closed period, (409) unless
     *  OPEN, and (422) when the date is not after the sheet's today. */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN claim is deferred, not a " + claim.getStatus() + " one");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim is deferred until a date after the sheet's today");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        record("CLAIM_DEFERRED", user, person, claim, claim.getNumber(), " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward re-dates an OPEN or PENDING claim: its due-by stays as it was fixed, a figure a
     *  statement bound it to stays bound, and its period follows the new date. Under period
     *  numbering, when the new date is in another period than the old (or none), the claim takes
     *  the next number of the new period's sequence and its old number is never given again.
     *  Refused (404) when missing, (403) for anyone but a steward, (423) when its old or new date
     *  is in a closed period, (409) unless OPEN or PENDING, and (422) when the new date is after
     *  the person's retirement date. */
    @Transactional
    public Claim redate(String sheetId, String user, long id, LocalDate date) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim");
        }
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN or PENDING claim is re-dated, not a " + claim.getStatus() + " one");
        }
        refuseIfClosed(sheetId, date);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        if (sheets.periodNumberingOn(sheetId) && !samePeriod(sheetId, old, date)) {
            String number = nextNumber(sheetId, date);
            if (claim.getNumber() != null) {
                retired.save(new RetiredNumber(sheetId, claim.getNumber()));
            }
            claim.setNumber(number);
        }
        claim.setDate(date);
        claims.flush();
        record("CLAIM_REDATED", user, person, claim, claim.getNumber(), " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Whether two dates fall in the same period (or both in none). */
    private boolean samePeriod(String sheetId, LocalDate a, LocalDate b) {
        return periods.containing(sheetId, a).map(Period::getId)
                .equals(periods.containing(sheetId, b).map(Period::getId));
    }

    /** A steward approves a PENDING claim: it becomes SETTLED, its due (its live figure, with its
     *  late charge) frozen now. Refused (409) unless PENDING, (403) for a clerk or the user who
     *  opened it, (423) when it is dated in a closed period, and (422) when its due is above the
     *  proof threshold in force on its date while it has no proof. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        Claim claim = pendingForSteward(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        BigDecimal due = shownDueOf(claim, person);
        refuseIfUnproven(claim, due);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(), " due=" + dueText(due));
        notifyWatchers(user, person, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Add a proof to a claim, raising its proofs count by one. Refused (404) when missing, (423)
     *  when dated in a closed period, and (409) when SETTLED or VOID. */
    @Transactional
    public Claim addProof(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim takes no more proofs");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        claim.addProof();
        claims.flush();
        record("CLAIM_PROOF_ADDED", user, person, claim, claim.getNumber(), " proofs=" + claim.getProofs());
        return claim;
    }

    /** Refuse (422) settling or approving a claim at a due (null: n/a) above the proof threshold in
     *  force on its date while it has no proof. */
    private void refuseIfUnproven(Claim claim, BigDecimal due) {
        if (claim.getProofs() > 0 || due == null) {
            return;
        }
        BigDecimal threshold = sheets.proofThresholdOn(claim.getSheetId(), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim whose due is above the proof threshold needs a proof first");
        }
    }

    /** Refuse (422) opening a claim at a due (null: n/a) above the note threshold in force on its
     *  date while its note is empty. */
    private void refuseIfNoteless(Claim claim, BigDecimal due) {
        if (due == null || (claim.getNote() != null && !claim.getNote().isBlank())) {
            return;
        }
        BigDecimal threshold = sheets.noteThresholdOn(claim.getSheetId(), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim whose due is above the note threshold needs a note first");
        }
    }

    /** A steward re-settles a SETTLED claim: its due is frozen again at the figure it would show
     *  today as an open claim bound by no statement (today's rates, grades, floor and late
     *  charge). Refused (404) when missing, (423) when dated in a closed period, (403) for anyone
     *  but a steward, and (409) unless SETTLED. */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a SETTLED claim is re-settled, not a " + claim.getStatus() + " one");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal old = claim.getSettledDue();
        BigDecimal due = flooredOf(claim,
                LateCharge.of(sheets.find(sheetId)).addToOpen(claim, rateFigureOf(claim, person), roundingOf(claim)));
        claim.setSettledDue(due);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_RESETTLED", user, person, claim, claim.getNumber(),
                " old=" + dueText(old) + " new=" + dueText(due));
        notifyWatchers(user, person, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward rejects a PENDING claim with a reason: it goes back to OPEN, bound again by the
     *  person's last statement if that statement bound it. Refused (409) unless PENDING, (403) for
     *  a clerk, and (423) when it is dated in a closed period. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        Claim claim = pendingForSteward(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward (or a role the permission matrix lets reassign) moves a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its
     *  number and date, is listed as the new person's newest claim, and loses any figure a
     *  statement bound it to (live again until the new person's next statement). Refused (404)
     *  when the claim or person is missing, (423) when it is dated in a closed period, (403) for a
     *  role the sheet's permission matrix does not let reassign, (409) when SETTLED or VOID or already the person's, and (422) when it is dated after
     *  the new person's retirement date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfClosed(sheetId, claim.getDate());
        settings.refuseUnlessAllowed(sheetId, user, Permission.REASSIGN);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (claim.getPersonId() == toPersonId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already that person's");
        }
        refuseIfRetired(to, claim.getDate());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        claim.setListed(claims.findLastListed(sheetId) + 1);
        claims.flush();
        record("CLAIM_REASSIGNED", user, to, claim, claim.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Split an amount off an OPEN claim: the claim keeps the rest of its amount and loses any
     *  figure a statement bound it to, and a new OPEN claim of the amount, with the same date and
     *  note, the next number, opened by the user, its due-by fixed now from the terms in force on
     *  the date (the person's own, else their grade's, else the sheet's), and naming the original's number as split-from, is listed as the person's newest.
     *  Refused (404) when missing, (423) when dated in a closed period, (403) for a role the
     *  sheet's permission matrix does not let open a claim, (409) unless OPEN and not deferred on
     *  the sheet's today, and (422) unless the amount is more than 0.00 and less than the claim's. */
    @Transactional
    public Split split(String sheetId, String user, long id, BigDecimal amount) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        settings.refuseUnlessAllowed(sheetId, user, Permission.OPEN);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN claim is split, not a " + claim.getStatus() + " one");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be split before then");
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The amount split off must be more than 0.00 and less than the claim's");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = claim.getDate();
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unbind();
        int terms = person.getTerms() != null ? person.getTerms()
                : sheets.find(sheetId).map(s -> s.termsFor(person.gradeOn(on), on)).orElse(Sheet.DEFAULT_TERMS);
        Claim fresh = new Claim(sheetId, person.getId(), amount, on, on.plusDays(terms), claim.getNote());
        fresh.setStatus(ClaimStatus.OPEN);
        fresh.setNumber(nextNumber(sheetId, on));
        fresh.setOpenedBy(user);
        fresh.setSplitFrom(claim.getNumber());
        fresh.setListed(claims.findLastListed(sheetId) + 1);
        Claim split = claims.saveAndFlush(fresh);
        logStatus(user, person, split);
        record("CLAIM_SPLIT", user, person, claim, claim.getNumber(), " into=" + split.getNumber());
        record("CLAIM_OPENED", user, person, split);
        rollup.recordChanges(sheetId, user, before);
        return new Split(claim, split);
    }

    /** The PENDING claim a steward decides on: refused (404) when missing, (423) when dated in a
     *  closed period, (409) unless PENDING, and (403) when the user is not a steward. */
    private Claim pendingForSteward(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a PENDING claim is approved or rejected, not a " + claim.getStatus() + " one");
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward approves or rejects a PENDING claim");
        }
        return claim;
    }

    /** Write the claim's status now to its log: by the user, on the sheet's today, with the due
     *  it shows at this moment. */
    private void logStatus(String user, Person person, Claim claim) {
        BigDecimal due = shownDueOf(claim, person);
        log.save(new ClaimLog(claim.getSheetId(), claim.getId(), claim.getStatus(), user,
                sheets.today(claim.getSheetId()), due == null ? null : due.setScale(2, RoundingMode.HALF_EVEN)));
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** Take a statement of a person: record their due-total now, and bind each of their claims
     *  that counts (OPEN, PENDING or SETTLED, but no unbound open one within a hold, nor a deferred one) to its figure now; the others
     *  are left unbound. Taken as of a date ({@code asOf}, not null), the sheet as it stood at the end
     *  of that date stands in for it now (see {@link #statementAsOf}). */
    @Transactional
    public Statement statement(String sheetId, String user, long personId, LocalDate asOf) {
        if (asOf != null) {
            return statementAsOf(sheetId, user, personId, asOf);
        }
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> counted = theirs.stream().filter(c -> counts(c, person, today)).toList();
        BigDecimal dueTotal = Sheet.capped(dueTotalOf(counted, person, today), sheets.cap(sheetId));
        for (Claim c : theirs) {
            if (counts(c, person, today)) {
                // the statement binds the figure; the late charge keeps following the sheet's today
                c.bind(c.getSettledDue() != null ? c.getSettledDue() : figureOf(c, person));
            } else {
                c.unbind();
            }
        }
        claims.flush();
        return stated(sheetId, user, person, theirs, dueTotal, counted.size(), today, null, before);
    }

    /** Take a statement of a person as of a date on or before the sheet's today: their claims that
     *  counted at the end of it are bound to their figures as they stood then (every change made
     *  while the sheet's today was on or before it counts, every later one does not), with that date
     *  standing in for the sheet's today in every figure; their other claims are left unbound.
     *  Refused (422) when the date is after the sheet's today, or the person was not yet added then. */
    private Statement statementAsOf(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        if (asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A statement is taken as of a date on or before the sheet's today");
        }
        HistoryService.AsOf then = history.asOf(sheetId, asOf);
        Person was = then.people().get(personId);
        if (was == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The person was not yet on the sheet on " + asOf);
        }
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        RollupService.Stated stated = RollupService.statedOn(then.sheet(), was, personId,
                List.copyOf(then.claims().values()), then.rates());
        Map<Long, BigDecimal> bound = new HashMap<>();
        then.claims().forEach((id, c) -> {
            if (stated.counted().containsKey(c)) {
                bound.put(id, stated.counted().get(c));
            }
        });
        for (Claim c : theirs) {
            if (bound.containsKey(c.getId())) {
                c.bind(bound.get(c.getId()));
            } else {
                c.unbind();
            }
        }
        claims.flush();
        return stated(sheetId, user, person, theirs, stated.dueTotal(), stated.counted().size(), today, asOf, before);
    }

    /** Record a statement just taken (as of {@code asOf}, or null) and audit it. */
    private Statement stated(String sheetId, String user, Person person, List<Claim> theirs, BigDecimal dueTotal,
            int counted, LocalDate today, LocalDate asOf, RollupService.Snapshot before) {
        statements.saveAndFlush(new PersonStatement(sheetId, person.getId(), dueTotal, counted, today, asOf, user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted
                + (asOf == null ? "" : " asof=" + asOf));
        rollup.recordChanges(sheetId, user, before);
        return new Statement(person, StatementSummary.of(statements.findByPersonIdOrderByIdAsc(person.getId())), theirs);
    }

    /** A steward cancels a person's last statement: it is removed, and every claim it bound is
     *  live again (a figure an earlier statement bound is not restored). Refused (404) when the
     *  person is missing, (403) for anyone but a steward, and (409) when the person has no
     *  statement. */
    @Transactional
    public Statement cancelStatement(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward cancels a statement");
        }
        List<PersonStatement> taken = statements.findByPersonIdOrderByIdAsc(personId);
        if (taken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person has no statement to cancel");
        }
        PersonStatement last = taken.get(taken.size() - 1);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        theirs.forEach(Claim::unbind);
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(sheetId, user, before);
        return new Statement(person, StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId)), theirs);
    }

    /** The number a claim dated {@code date} takes as it is opened: under period numbering (set,
     *  and the sheet's today on or after it) the next within the period containing the date
     *  (<period name>-<n>), or X-<n> when none does; otherwise the sheet's next number. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.periodNumberingOn(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
        }
        // held until the transaction ends, so two claims opened at once are numbered one after the other
        sheets.lock(sheetId);
        String prefix = periods.containing(sheetId, date).map(Period::getName).orElse(NO_PERIOD) + "-";
        List<String> given = new ArrayList<>(claims.findNumbers(sheetId));
        given.addAll(retired.findNumbers(sheetId));
        int last = given.stream()
                .filter(n -> n.startsWith(prefix))
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.matches("\\d{1,9}"))
                .mapToInt(Integer::parseInt)
                .max().orElse(0);
        return prefix + (last + 1);
    }

    /** Refuse (423) a change to a claim dated in a closed period, holding the sheet locked until
     *  the transaction ends so a period cannot be closed meanwhile. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.refuseIfClosed(sheetId, date);
    }

    /** Refuse (409) a change to a claim whose editor another user holds open. */
    private static void refuseIfLocked(Claim claim, String user) {
        if (claim.isLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is being edited by " + claim.getLockedBy());
        }
    }

    /** The number a claim of the amount corrects: none unless the amount is negative, when it must
     *  be the number of a SETTLED claim of the same person, or the claim is refused (422). */
    private String correctedBy(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        if (corrects == null
                || !claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must correct a settled claim of the same person");
        }
        return corrects;
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.retiredBefore(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The date is after the person's retirement date");
        }
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN (or PENDING) and SETTLED ones
     *  do, but not an open one dated within a hold of its person, nor one of a person whose
     *  retirement date the sheet's today has reached, unless bound by a statement, nor one deferred
     *  on the sheet's today. */
    private static boolean counts(Claim c, Person person, LocalDate today) {
        return c.getStatus() == ClaimStatus.SETTLED
                || (c.getStatus().isOpen() && !c.isDeferredOn(today)
                        && (c.isBound() || !(person.heldOn(c.getDate()) || person.retiredOn(today))));
    }

    /** The due a claim shows: the due fixed when it was settled, else its figure with its late
     *  charge on top, raised to the floor in force on its date. */
    private BigDecimal shownDueOf(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return flooredOf(claim,
                LateCharge.of(sheets.find(claim.getSheetId())).addTo(claim, figureOf(claim, person), roundingOf(claim)));
    }

    /** A due (null: n/a, which stays n/a) raised to the sheet's floor in force on the claim's date; a
     *  correction's never is. */
    private BigDecimal flooredOf(Claim claim, BigDecimal due) {
        if (due == null || claim.isCorrection()) {
            return due;
        }
        return due.max(sheets.floorOn(claim.getSheetId(), claim.getDate()));
    }

    /** A claim's figure before any late charge or floor: the one a statement bound it to (null: n/a),
     *  else (unbound, or PENDING) its rate figure. */
    private BigDecimal figureOf(Claim claim, Person person) {
        return claim.isBound() ? claim.getStatedDue() : rateFigureOf(claim, person);
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private BigDecimal dueTotalOf(List<Claim> counted, Person person, LocalDate today) {
        List<BigDecimal> dues = counted.stream().map(c -> shownDueOf(c, person)).filter(d -> d != null).toList();
        if (rates.findInForce(person.getSheetId(), person.gradeOn(today), today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The claim's amount times the rate, in force on the claim's date, of its person's grade then, divided by
     *  100, rounded as its rounding policy has it; null with no rate in force then. */
    private BigDecimal rateFigureOf(Claim claim, Person person) {
        return rates.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, roundingOf(claim)))
                .orElse(null);
    }

    /** How the claim's live figures round: by the sheet's policy date against its date, or for a
     *  correction the date of the claim it corrects. */
    private RoundingMode roundingOf(Claim claim) {
        LocalDate on = claim.getCorrects() == null ? claim.getDate()
                : claims.findFirstBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                        .map(Claim::getDate).orElse(claim.getDate());
        return Rounding.of(sheets.find(claim.getSheetId())).on(on);
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber());
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void record(String event, String user, Person person, Claim claim, String number) {
        record(event, user, person, claim, number, "");
    }

    private void record(String event, String user, Person person, Claim claim, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().setScale(2).toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }

    /** Notify each user watching the person that one of their claims was settled, in the order
     *  they started watching. */
    private void notifyWatchers(String user, Person person, String number) {
        for (PersonWatch w : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + person.getSheetId() + " by=" + user + " user=" + w.getUserName()
                    + " person=" + person.getName() + " number=" + number);
        }
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
