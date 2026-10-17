package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims kept on a sheet, newest first. A claim without a date takes the sheet's today; a
 * missing note is stored empty.
 */
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

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        sheets.load(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Claim claim = claims.save(new Claim(sheetId, personId, scaled(amount),
                date != null ? date : sheets.today(sheetId), note != null ? note : ""));
        record("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note != null ? note : "");
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        record("CLAIM_EDITED", user, person, claim);
        return claim;
    }

    /**
     * Move a claim to the next status; a change its status does not allow is refused (409). Opening
     * gives the claim the sheet's next number; voiding clears it (the number is not given again).
     * Settling fixes the claim's due at its person's grade's rate in force on the claim's date.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long claimId, ClaimStatus next) {
        // Lock the sheet before reading the claim, so claims opened at the same moment get different numbers.
        Sheet sheet = next == ClaimStatus.OPEN
                ? sheets.lock(sheetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"))
                : null;
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        Long number = next == ClaimStatus.OPEN ? Long.valueOf(sheet.takeClaimNumber()) : claim.getNumber();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        claim.setStatus(next);
        claim.setNumber(next == ClaimStatus.VOID ? null : number);
        List<String> extra = new ArrayList<>();
        if (next == ClaimStatus.SETTLED) {
            BigDecimal due = rates.inForce(sheetId, person.getGrade(), claim.getDate())
                    .map(rate -> claim.getAmount().multiply(rate.getRate())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                    .orElse(null);
            claim.setSettledDue(due);
            extra.add("due=" + (due == null ? "n/a" : due.toPlainString()));
        }
        record(switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        }, user, person, claim, number, extra);
        return claim;
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber(), List.of());
    }

    /**
     * Amounts are written with two decimals, as shown, and dates as yyyy-mm-dd. A claim with a number
     * (or, when voided, the number it had) adds number=&lt;number&gt;; any extra fields follow it.
     */
    private void record(String event, String user, Person person, Claim claim, Long number, List<String> extra) {
        List<String> fields = new ArrayList<>(List.of("person=" + person.getName(),
                "amount=" + claim.getAmount().toPlainString(), "date=" + claim.getDate()));
        if (number != null) {
            fields.add("number=" + number);
        }
        fields.addAll(extra);
        audit.change(event, claim.getSheetId(), user, fields.toArray(String[]::new));
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
