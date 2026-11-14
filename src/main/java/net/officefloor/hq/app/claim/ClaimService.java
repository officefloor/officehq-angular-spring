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
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person on the sheet; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, on, noteOf(note)));
        record("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    /** Change a claim's amount, date and note; a missing date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(noteOf(note));
        claims.flush();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        record("CLAIM_EDITED", user, person, claim);
        return claim;
    }

    /** Move a claim to a new status; a move its status does not allow is refused (409). */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().canMoveTo(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        // the VOIDED record carries the number the claim had
        Integer number = claim.getNumber();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
        } else if (to == ClaimStatus.VOID) {
            claim.setNumber(null);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(dueOf(claim, person));
        }
        claims.flush();
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalArgumentException("A claim cannot return to DRAFT");
        };
        if (to == ClaimStatus.SETTLED) {
            BigDecimal due = claim.getSettledDue();
            record(event, user, person, claim, number, " due=" + (due == null ? "n/a" : due.toPlainString()));
        } else {
            record(event, user, person, claim, number);
        }
        return claim;
    }

    /** The claim's amount times its person's grade rate, divided by 100, rounded half up; null with no rate. */
    private BigDecimal dueOf(Claim claim, Person person) {
        return rates.findBySheetIdAndGrade(claim.getSheetId(), person.getGrade())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber());
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void record(String event, String user, Person person, Claim claim, Integer number) {
        record(event, user, person, claim, number, "");
    }

    private void record(String event, String user, Person person, Claim claim, Integer number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().setScale(2).toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
