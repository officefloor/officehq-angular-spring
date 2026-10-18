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

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; without a date it is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Claim saved = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOrToday(sheetId, date), noteOf(note)));
        audit(sheetId, user, "CLAIM_ADDED", person, saved, saved.getNumber());
        return saved;
    }

    /** Change a claim's amount, date and note; without a date it is dated the sheet's today. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(amount);
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        audit(sheetId, user, "CLAIM_EDITED", person, saved, saved.getNumber());
        return saved;
    }

    /** Move a claim to its next status; a change its current status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Sheet sheet = sheets.lock(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Integer number = next == ClaimStatus.OPEN ? Integer.valueOf(sheet.takeClaimNumber()) : claim.getNumber();
        claim.setStatus(next);
        claim.setNumber(next == ClaimStatus.VOID ? null : number);
        if (next == ClaimStatus.SETTLED) {
            claim.setDue(rates.findBySheetIdAndGrade(sheetId, person.getGrade())
                    .map(rate -> due(claim.getAmount(), rate))
                    .orElse(null));
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            default -> "CLAIM_VOIDED";
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getDue() == null ? "n/a" : saved.getDue().setScale(2).toPlainString())
                : "";
        audit(sheetId, user, event, person, saved, number, suffix);
        return saved;
    }

    /** The amount times the rate's percent, rounded half up to cents (as the screen works it out). */
    private static BigDecimal due(BigDecimal amount, Rate rate) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd; a claim
     * with a number (or, when voided, that had one) ends with it.
     */
    private void audit(String sheetId, String user, String event, Person person, Claim claim, Integer number) {
        audit(sheetId, user, event, person, claim, number, "");
    }

    /** As above, with {@code suffix} (fields a later change added) at the end of the line. */
    private void audit(String sheetId, String user, String event, Person person, Claim claim, Integer number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
