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

/**
 * The claims on a sheet: added to a person and edited (amount, date, note). A claim given no date
 * takes the sheet's today. A claim starts as DRAFT and moves through its statuses (see
 * {@link ClaimStatus}); a change its status does not allow is refused. Settling a claim fixes its
 * due at the rate its person's grade has then for the claim's date; an open claim's due stays live.
 * A claim records who opened it, and that user cannot settle it (four eyes).
 */
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

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOrToday(sheetId, date), note));
        audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, added));
        return added;
    }

    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        claim.setAmount(amount);
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    /**
     * Move a claim to a new status, refused (409) when its current status does not allow it, and
     * refused (403) when the user who opened it tries to settle it.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        // lock the sheet's numbering before reading the claim, so a claim is opened (and numbered) once
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it.");
        }
        // the audit line carries the number the claim had, before voiding clears it
        Integer recorded = next == ClaimStatus.OPEN ? number : claim.getNumber();
        Person person = person(sheetId, claim.getPersonId());
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due(sheetId, person, claim));
        } else if (next == ClaimStatus.VOID) {
            claim.setNumber(null);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, sheetId, user, person, saved)
                + (recorded != null ? " number=" + recorded : "")
                + (next == ClaimStatus.SETTLED ? " due=" + dueText(saved.getSettledDue()) : ""));
        return saved;
    }

    /**
     * The due on a claim's amount at the rate for the person's grade in force on the claim's date,
     * half to even to the cent; null with no rate in force then.
     */
    private BigDecimal due(String sheetId, Person person, Claim claim) {
        return rates.findInForce(sheetId, person.getGrade(), claim.getDate()).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName() + " amount="
                + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " date=" + claim.getDate();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
