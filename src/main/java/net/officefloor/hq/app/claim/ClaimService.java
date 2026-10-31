package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
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

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, RateRepository rates, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.rates = rates;
        this.audit = audit;
    }

    /** Every claim on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today, a missing note is empty. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), date != null ? date : today, noteOf(note)));
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, added, added.getNumber()));
        return added;
    }

    /** Change a claim's amount, date and note; a missing date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        Claim edited = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(edited.getPersonId(), sheetId).orElseThrow();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, person, edited, edited.getNumber()));
        return edited;
    }

    /**
     * Move a claim to a new status; a move its current status does not allow is refused. Opening a
     * claim gives it the sheet's next number; voiding it clears its number; settling it fixes its due
     * at its person's grade's rate in force on its date, as rates are now, so later rate changes leave it as it is. The sheet is locked
     * first, so moves on one sheet are made one at a time and no number is given twice.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Integer number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = sheet.nextClaimNumber();
            claim.setNumber(number);
        } else if (next == ClaimStatus.VOID) {
            claim.setNumber(null);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(Rate.inForceOn(rates.findBySheetIdAndGrade(sheetId, person.getGrade()), claim.getDate())
                    .map(Rate::getRate).map(rate -> dueOn(claim.getAmount(), rate)).orElse(null));
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
        return changed;
    }

    /** An audit line about a claim; one with a number (or, once void, that had one) ends with it. */
    private static String entry(String event, String user, Person person, Claim claim, Integer number) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number);
    }

    /** The due on an amount at a rate (a percent): amount × rate / 100, rounded half up to the cent. */
    private static BigDecimal dueOn(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
