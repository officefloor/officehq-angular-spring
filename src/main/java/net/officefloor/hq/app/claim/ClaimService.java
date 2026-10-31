package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet have made. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RateRepository rates;
    private final Audit audit;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, RateRepository rates, Audit audit, Rollup rollup, PeriodRepository periods,
            RoleService roles) {
        this.periods = periods;
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
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today, a missing note is empty. Refused
     *  when its date is inside a closed period. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        refuseIfClosed(sheetId, date != null ? date : today);
        refuseIfRetired(person, date != null ? date : today);
        Rollup.Figures before = rollup.of(sheetId);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), date != null ? date : today, noteOf(note)));
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, added, added.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** Change a claim's amount, date and note; a missing date takes the sheet's today. Only a draft can be
     *  changed: to change an open claim, void it and add a new one. Refused when its date, as it is or as it
     *  would become, is inside a closed period. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, date != null ? date : today);
        refuseIfRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(), date != null ? date : today);
        Rollup.Figures before = rollup.of(sheetId);
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        Claim edited = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(edited.getPersonId(), sheetId).orElseThrow();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, person, edited, edited.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /**
     * Move a claim to a new status; a move its current status does not allow is refused. Opening a
     * claim gives it the sheet's next number and records who opened it; voiding it keeps its number, which is
     * never given again; settling it fixes its due
     * at its person's grade's rate in force on its date, as rates are now, so later rate changes leave it as it is. The user
     * who opened a claim cannot settle it, and a clerk can settle it only when that due is at or below the sheet's
     * clerk limit (a claim with no due is within any limit); a steward settles anything. A claim dated inside a closed period cannot move. The sheet is locked
     * first, so moves on one sheet are made one at a time and no number is given twice.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = Rate.inForceOn(rates.findBySheetIdAndGrade(sheetId, person.getGrade()), claim.getDate())
                .map(Rate::getRate).map(rate -> floored(dueOn(claim.getAmount(), rate), sheet.getFloor())).orElse(null);
        if (next == ClaimStatus.SETTLED) {
            refuseOverClerkLimit(sheet, user, due);
        }
        Rollup.Figures before = rollup.of(sheetId);
        Integer number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = sheet.nextClaimNumber();
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        Claim changed = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String line = entry(event, user, person, changed, number);
        if (next == ClaimStatus.SETTLED) {
            line += " due=" + (changed.getSettledDue() == null ? "n/a" : changed.getSettledDue().toPlainString());
        }
        audit.recordOnCommit(line);
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** Refuse a clerk settling a claim whose due is above the sheet's clerk limit; a steward settles anything,
     *  and a claim with no due is within any limit. */
    private void refuseOverClerkLimit(Sheet sheet, String user, BigDecimal due) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return;
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheet.getId(), user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A clerk cannot settle a claim whose due is above the clerk limit of " + limit.toPlainString());
        }
    }

    /** Refuse a change to a claim dated inside a closed period of the sheet. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's date is inside a closed period");
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
    private static String entry(String event, String user, Person person, Claim claim, Integer number) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number);
    }

    /** The due on an amount at a rate (a percent): amount × rate / 100, rounded half to even to the cent. */
    public static BigDecimal dueOn(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** A live due raised to the sheet's floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor : due;
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
