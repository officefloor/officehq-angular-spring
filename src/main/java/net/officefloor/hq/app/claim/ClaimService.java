package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
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

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim to a person; a claim with no date is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
        Person person = person(sheetId, personId);
        LocalDate dated = dateOr(sheetId, date);
        refuseIfClosed(sheetId, dated);
        refuseIfRetired(person, dated);
        refuseIfNotCorrecting(sheetId, personId, amount, corrects);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note), corrects));
        recordChange("CLAIM_ADDED", user, person, claim);
        rollup.recordChanges(user, before);
        return claim;
    }

    /** Change a draft claim's amount, date and note; a blank date takes the sheet's today. A claim that
     *  is no longer a draft is refused: to change an open claim, void it and add a new one. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
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
     *  by the user who opened the claim, and settling by a clerk a claim whose due is above the
     *  sheet's clerk limit (a due of n/a is within any limit; a steward settles anything). */
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
        if (next == ClaimStatus.SETTLED) refuseIfAboveClerkLimit(sheetId, user, dueNow);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Integer number = next == ClaimStatus.OPEN ? Integer.valueOf(sheets.nextClaimNumber(sheetId)) : claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) claim.setOpenedBy(user);
        // A voided claim keeps its number, which is never given to another claim.
        claim.setNumber(number);
        if (next == ClaimStatus.SETTLED) claim.setSettledDue(dueNow);
        Claim saved = claims.saveAndFlush(claim);
        String event = EVENTS.get(next);
        if (next == ClaimStatus.SETTLED) {
            BigDecimal due = saved.getSettledDue();
            recordChange(event, user, person, saved, number, " due=" + (due == null ? "n/a" : due.toPlainString()));
        } else {
            recordChange(event, user, person, saved, number, "");
        }
        rollup.recordChanges(user, before);
        return saved;
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED", ClaimStatus.SETTLED, "CLAIM_SETTLED", ClaimStatus.VOID, "CLAIM_VOIDED");

    /** Refuse a clerk's settling of a claim whose due is above the sheet's clerk limit. */
    private void refuseIfAboveClerkLimit(String sheetId, String user, BigDecimal due) {
        if (due == null || settings.roleOf(sheetId, user) == Role.STEWARD) return;
        BigDecimal limit = settings.clerkLimit(sheetId);
        if (limit != null && due.compareTo(limit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A clerk cannot settle a claim whose due is above the clerk limit of " + limit.toPlainString() + ".");
        }
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
    private void refuseIfNotCorrecting(String sheetId, long personId, BigDecimal amount, Integer corrects) {
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
        return LateCharge.dueOf(claim, figure(sheetId, person, claim), settings.terms(sheetId),
                sheets.today(sheetId), settings.latePct(sheetId));
    }

    /** The figure under the claim's due, before any late charge: the due fixed when it was settled, else the figure
     *  its person's last statement bound it to, else its live due; null when that is n/a. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isStated()) return claim.getStatedDue();
        return dueNow(sheetId, person, claim);
    }

    /** What is due on the claim now at the rate in force on the claim's date of its person's grade in
     *  force on that date, rounded
     *  half to even and at least the sheet's floor (a correction has no floor); null when no rate of the
     *  grade applies by then. */
    private BigDecimal dueNow(String sheetId, Person person, Claim claim) {
        Rate rate = rates.inForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
        if (rate == null) return null;
        BigDecimal due = claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        return claim.isCorrection() ? due : due.max(settings.floor(sheetId));
    }

    private void recordChange(String event, String user, Person person, Claim claim) {
        recordChange(event, user, person, claim, claim.getNumber(), "");
    }

    /** The claim's audit line; a claim with a number carries it, then any extra fields. */
    private void recordChange(String event, String user, Person person, Claim claim, Integer number, String extra) {
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
