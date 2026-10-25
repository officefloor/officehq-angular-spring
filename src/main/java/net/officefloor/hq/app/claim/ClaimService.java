package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
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
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
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

    /** Add a claim to a person; a claim with no date is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOr(sheetId, date), noteOf(note)));
        recordChange("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    /** Change a claim's amount, date and note; a blank date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.set(amount, dateOr(sheetId, date), noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_EDITED", user, person(sheetId, saved.getPersonId()), saved);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        Person person = person(sheetId, claim.getPersonId());
        Integer number = next == ClaimStatus.OPEN ? Integer.valueOf(sheets.nextClaimNumber(sheetId)) : claim.getNumber();
        claim.setStatus(next);
        // A void claim keeps no number; the number it had is never given to another claim.
        claim.setNumber(next == ClaimStatus.VOID ? null : number);
        if (next == ClaimStatus.SETTLED) claim.setSettledDue(dueNow(sheetId, person, claim));
        Claim saved = claims.saveAndFlush(claim);
        String event = EVENTS.get(next);
        if (next == ClaimStatus.SETTLED) {
            BigDecimal due = saved.getSettledDue();
            recordChange(event, user, person, saved, number, " due=" + (due == null ? "n/a" : due.toPlainString()));
        } else {
            recordChange(event, user, person, saved, number, "");
        }
        return saved;
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED", ClaimStatus.SETTLED, "CLAIM_SETTLED", ClaimStatus.VOID, "CLAIM_VOIDED");

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** What is due on the claim now at its person's grade's rate in force on the claim's date, rounded
     *  half to even; null when no rate of the grade applies by then. */
    private BigDecimal dueNow(String sheetId, Person person, Claim claim) {
        Rate rate = rates.inForce(sheetId, person.getGrade(), claim.getDate());
        return rate == null ? null
                : claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
    }

    private void recordChange(String event, String user, Person person, Claim claim) {
        recordChange(event, user, person, claim, claim.getNumber(), "");
    }

    /** The claim's audit line; a claim with a number (or that had one, when voided) carries it, then any extra fields. */
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
