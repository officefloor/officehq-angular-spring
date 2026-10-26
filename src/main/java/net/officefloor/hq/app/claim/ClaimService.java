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

/** The claims people make on a sheet. */
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

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Adds a claim for the person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        Claim added = claims.saveAndFlush(
                new Claim(sheetId, personId, scaled(amount), dateOrToday(sheetId, date), noteOf(note)));
        audit.recordOnCommit(record("CLAIM_ADDED", by, person, added));
        return added;
    }

    @Transactional
    public Claim update(String by, String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(scaled(amount));
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", by, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    /** Moves the claim to the status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next) {
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().canMoveTo(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        Long number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
        } else if (next == ClaimStatus.VOID) {
            // the number is cleared, never given to another claim; the audit line still carries it
            claim.setNumber(null);
        }
        Person person = person(sheetId, claim.getPersonId());
        String due = null;
        if (next == ClaimStatus.SETTLED) {
            // the due is fixed at the rate the person's grade has now; with no rate it stays live
            BigDecimal fixed = rates.findBySheetIdAndGrade(sheetId, person.getGrade()).map(Rate::getPct)
                    .map(pct -> dueOn(claim.getAmount(), pct)).orElse(null);
            claim.setSettledDue(fixed);
            due = fixed == null ? "n/a" : fixed.toPlainString();
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, by, person, saved, number) + (due != null ? " due=" + due : ""));
        return saved;
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    private static String record(String event, String by, Person person, Claim c) {
        return record(event, by, person, c, c.getNumber());
    }

    /** The audit line about a claim; one that has a number ends with it. */
    private static String record(String event, String by, Person person, Claim c, Long number) {
        return event + " sheet=" + c.getSheetId() + " by=" + by + " person=" + person.getName()
                + " amount=" + c.getAmount().toPlainString() + " date=" + c.getDate()
                + (number != null ? " number=" + number : "");
    }

    /** The due on an amount at a rate: amount times rate divided by 100, rounded half up to two decimals. */
    private static BigDecimal dueOn(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.load(sheetId).getToday();
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.strip();
    }
}
