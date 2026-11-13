package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on a sheet make. */
@Service
public class ClaimService {

    /** Written for a due that has no rate to work from. */
    private static final String NO_RATE = "n/a";

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
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), on, noteOf(note)));
        record("CLAIM_ADDED", sheetId, user, person, added);
        return added;
    }

    /** Change a claim's amount, date and note; a missing date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, String user, Long personId, Long id, BigDecimal amount, LocalDate date,
            String note) {
        Person person = person(sheetId, personId);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, so a claim opened at the same moment elsewhere waits and the claim
        // read below is its latest state.
        Long number = to == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (!claim.getStatus().canMoveTo(to)) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        Long had = claim.getNumber();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            claim.setNumber(number);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(dueOf(sheetId, person, claim));
        } else if (to == ClaimStatus.VOID) {
            claim.setNumber(null);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException("A claim never returns to DRAFT");
        };
        String line = line(event, sheetId, user, person, saved, had != null ? had : saved.getNumber());
        if (to == ClaimStatus.SETTLED) {
            line += " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE);
        }
        audit.recordOnCommit(line);
        return saved;
    }

    /** The due on a claim at the person's grade rate in force on the claim's date: amount times rate
     *  divided by 100, rounded half to even to two decimals; null when no rate applies. */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        BigDecimal amount = claim.getAmount();
        return rates.rateOn(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getRate)
                .map(rate -> amount.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber());
    }

    /** One audit line about a claim; a claim with a number carries it at the end. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, Long number) {
        audit.recordOnCommit(line(event, sheetId, user, person, claim, number));
    }

    private static String line(String event, String sheetId, String user, Person person, Claim claim, Long number) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
