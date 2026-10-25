package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final Rollup rollup;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            SheetRepository sheetRows, PeriodRepository periods, SettingService settings, Rollup rollup,
            Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.periods = periods;
        this.settings = settings;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's claims, newest first by their place in the list. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Add a claim to a person; a claim with no date is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = person(sheetId, personId);
        LocalDate dated = dateOr(sheetId, date);
        refuseIfClosed(sheetId, dated);
        refuseIfRetired(person, dated);
        refuseIfNotCorrecting(sheetId, personId, amount, corrects);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        // The due-by is fixed now, from the terms of the person's grade in force on the claim's date.
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note), corrects,
                settings.terms(sheetId, person.gradeOn(dated), dated));
        // The sheet is locked (refuseIfClosed), so no other claim takes the same place.
        claim.setListed(claims.lastListed(sheetId) + 1);
        claim = claims.saveAndFlush(claim);
        logStatus(sheetId, user, person, claim);
        recordChange("CLAIM_ADDED", user, person, claim);
        rollup.recordChanges(user, before);
        return claim;
    }

    /** Change a draft claim's amount, date and note; a blank date takes the sheet's today. A claim that
     *  is no longer a draft is refused: to change an open claim, void it and add a new one. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        LocalDate dated = dateOr(sheetId, date);
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, dated);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
        refuseIfRetired(person(sheetId, claim.getPersonId()), dated);
        refuseIfNotCorrecting(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.set(amount, dated, noteOf(note), corrects);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_EDITED", user, person(sheetId, saved.getPersonId()), saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused, as is settling
     *  by the user who opened the claim. A clerk's settling of a claim whose due is above the sheet's
     *  clerk limit leaves it PENDING for a steward to approve (a due of n/a is within any limit; a
     *  steward settles anything). */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        // Worked out while the claim is still open: settling freezes the due, its late charge included.
        BigDecimal dueNow = next == ClaimStatus.SETTLED ? due(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED && isAboveClerkLimit(sheetId, user, dueNow)) next = ClaimStatus.PENDING;
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        String number = next == ClaimStatus.OPEN ? nextNumber(sheetId, claim) : claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) claim.setOpenedBy(user);
        // A voided claim keeps its number, which is never given to another claim.
        claim.setNumber(number);
        if (next == ClaimStatus.SETTLED) claim.setSettledDue(dueNow);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        String event = EVENTS.get(next);
        if (next == ClaimStatus.SETTLED) {
            recordChange(event, user, person, saved, number, " due=" + text(saved.getSettledDue()));
        } else {
            recordChange(event, user, person, saved, number, "");
        }
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet, as their newest claim. It
     *  keeps its number and date and loses any figure a statement bound it to (it is live again until
     *  its new person's next statement). Refused unless the user is a steward of the sheet, and for a
     *  settled or voided claim, a claim dated inside a closed period, a move to the claim's own person,
     *  or to a person retired by the claim's date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDate());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward moves a claim to another person.");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be moved to another person.");
        }
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s.");
        }
        refuseIfRetired(to, claim.getDate());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.moveTo(to.getId(), claims.lastListed(sheetId) + 1);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REASSIGNED", user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Approve a PENDING claim: it is SETTLED, its due (its live figure and any late charge) frozen
     *  now. Refused unless the user is a steward of the sheet other than the one who opened it. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        Claim claim = pending(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot approve it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal dueNow = due(sheetId, person, claim);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(dueNow);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_APPROVED", user, person, saved, saved.getNumber(), " due=" + text(saved.getSettledDue()));
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Re-settle a SETTLED claim: its due is frozen again at what it would be today as an open claim
     *  bound by no statement (today's rates, grades, floor and late charge). Refused unless the user is
     *  a steward of the sheet, and for a claim that is not SETTLED or is dated inside a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDate());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim.");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        BigDecimal dueNow = LateCharge.dueAsOpen(claim, dueNow(sheetId, person, claim), sheets.today(sheetId),
                settings.latePct(sheetId, claim.getDate()), rounding(sheetId, claim));
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setSettledDue(dueNow);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_RESETTLED", user, person, saved, saved.getNumber(),
                " old=" + text(old) + " new=" + text(saved.getSettledDue()));
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Reject a PENDING claim for a reason: it is OPEN again, bound again by its person's last
     *  statement if that statement bound it. Refused unless the user is a steward of the sheet. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        Claim claim = pending(sheetId, user, id);
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REJECTED", user, person, saved, saved.getNumber(), " reason=" + reason);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** The PENDING claim a steward approves or rejects, locked; refused when it is not PENDING, is
     *  dated inside a closed period, or the user is not a steward of the sheet. */
    private Claim pending(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for approval.");
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim.");
        }
        return claim;
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED", ClaimStatus.PENDING, "CLAIM_PENDING",
            ClaimStatus.SETTLED, "CLAIM_SETTLED", ClaimStatus.VOID, "CLAIM_VOIDED");

    private static String text(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The number a claim takes as it is opened: while the sheet's period numbering is in force (its
     *  today on or after the setting's date), the next of the claim's period, <period name>-<n>, or of
     *  claims in no period, X-<n>; otherwise the sheet's next sheet-wide number. */
    private String nextNumber(String sheetId, Claim claim) {
        LocalDate from = settings.periodNumberingFrom(sheetId);
        if (from == null || sheets.today(sheetId).isBefore(from)) {
            return Integer.toString(sheets.nextClaimNumber(sheetId));
        }
        Period period = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter((p) -> p.contains(claim.getDate())).findFirst().orElse(null);
        if (period == null) return "X-" + sheets.nextSequenceNumber(sheetId, "none");
        return period.getName() + "-" + sheets.nextSequenceNumber(sheetId, "period:" + period.getId());
    }

    /** Whether the user is a clerk settling a claim whose due is above the sheet's clerk limit. */
    private boolean isAboveClerkLimit(String sheetId, String user, BigDecimal due) {
        if (due == null || settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due.compareTo(limit) > 0;
    }

    /** Refuse a change to a claim dated inside a closed period. The sheet is locked first, as closing
     *  a period locks it, so a claim cannot change in a period while it is being closed. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdate(sheetId);
        if (periods.isClosedOn(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is dated inside a closed period.");
        }
    }

    /** Refuse a correction (a negative amount) unless it names, by number, a settled claim of the same person. */
    private void refuseIfNotCorrecting(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) return;
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED).isPresent();
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must name, by number, a settled claim of the same person that it corrects.");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.isRetiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is dated after " + person.getName() + " retired.");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** What is due on the claim now: its figure with the late charge on top while it is open and
     *  overdue (the charge is never bound by a statement); null when the figure is n/a. */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        return LateCharge.dueOf(claim, figure(sheetId, person, claim), sheets.today(sheetId),
                settings.latePct(sheetId, claim.getDate()), rounding(sheetId, claim));
    }

    /** The figure under the claim's due, before any late charge: the due fixed when it was settled, else the figure
     *  its person's last statement bound it to (unless it is PENDING, which no statement binds), else its live due;
     *  null when that is n/a. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        return dueNow(sheetId, person, claim);
    }

    /** What is due on the claim now at the rate in force on the claim's date of its person's grade in
     *  force on that date, rounded
     *  as the claim's {@link Rounding} decides and at least the sheet's floor (a correction has no floor); null when no rate of the
     *  grade applies by then. */
    private BigDecimal dueNow(String sheetId, Person person, Claim claim) {
        Rate rate = rates.inForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
        if (rate == null) return null;
        BigDecimal due = claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2,
                rounding(sheetId, claim));
        return claim.isCorrection() ? due : due.max(settings.floor(sheetId, claim.getDate()));
    }

    /** How the claim's live figures round under the sheet's policy date: by the date of the claim a
     *  correction corrects, else by its own. */
    private RoundingMode rounding(String sheetId, Claim claim) {
        Claim corrected = claim.isCorrection() && claim.getCorrects() != null
                ? claims.findBySheetIdAndPersonIdAndNumber(sheetId, claim.getPersonId(), claim.getCorrects()).orElse(null)
                : null;
        return Rounding.of(Rounding.dateOf(claim, corrected),
                Rounding.policyDate(settings.policyDate(sheetId), sheets.today(sheetId)));
    }

    /** Log the status the claim has just taken, with its due as worked out now. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.logStatus(user, sheets.today(sheetId), due(sheetId, person, claim));
    }

    private void recordChange(String event, String user, Person person, Claim claim) {
        recordChange(event, user, person, claim, claim.getNumber(), "");
    }

    /** The claim's audit line; a claim with a number carries it, then any extra fields. */
    private void recordChange(String event, String user, Person person, Claim claim, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number) + extra);
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
