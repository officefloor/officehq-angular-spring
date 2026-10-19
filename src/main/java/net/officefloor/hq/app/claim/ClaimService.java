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

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a claim without a date is dated the sheet's today. */
    @Transactional
    public Claim add(String user, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        Person person = requirePerson(sheetId, personId);
        Claim claim = claims.saveAndFlush(
                new Claim(sheetId, personId, amount, date != null ? date : today, noteOf(note)));
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, claim));
        return claim;
    }

    @Transactional
    public Claim update(String user, String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, requirePerson(sheetId, claim.getPersonId()), claim));
        return claim;
    }

    /** Move a claim to another status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String user, String sheetId, Long id, ClaimStatus next) {
        sheets.today(sheetId); // the sheet must exist
        // Taking the number first locks the sheet, so claims are opened one at a time; a refused
        // open rolls back and gives the number back.
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        // Four eyes: whoever opened a claim cannot also settle it.
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        // A voided claim's line carries the number it had.
        String entry = entry(EVENTS.get(next), user, person, claim);
        if (next == ClaimStatus.SETTLED) {
            // Settling fixes the due at the rate its grade has on its date; with no rate it stays live.
            claim.setSettledDue(dueOf(claim, person.getGrade()));
            entry += " due=" + (claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : "n/a");
        }
        if (next == ClaimStatus.VOID) {
            claim.setNumber(null);
        }
        claims.flush();
        audit.recordOnCommit(entry);
        return claim;
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED",
            ClaimStatus.SETTLED, "CLAIM_SETTLED",
            ClaimStatus.VOID, "CLAIM_VOIDED");

    /** What is due on a claim at the rate its grade has in force on the claim's date, rounded half to even to two
     * decimals; none when no rate of the grade applies then. */
    private BigDecimal dueOf(Claim claim, String grade) {
        BigDecimal amount = claim.getAmount();
        return Rate.inForce(rates.findBySheetIdAndGrade(claim.getSheetId(), grade), claim.getDate())
                .map(r -> amount.multiply(r.getRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private Person requirePerson(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** The amount is written as the screen shows it, with two decimals; the date as yyyy-mm-dd; a
     * claim with a number ends with it. */
    private static String entry(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (claim.getNumber() != null ? " number=" + claim.getNumber() : "");
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
