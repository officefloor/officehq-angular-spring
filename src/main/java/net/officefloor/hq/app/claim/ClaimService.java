package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people's claims on a sheet. A claim with no date is dated the sheet's today. */
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

    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        Claim saved = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOr(sheetId, date), noteOf(note)));
        audit.recordOnCommit(record("CLAIM_ADDED", user, person, saved));
        return saved;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        claim.setAmount(amount);
        claim.setDate(dateOr(sheetId, date));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, saved.getPersonId()));
        audit.recordOnCommit(record("CLAIM_EDITED", user, person, saved));
        return saved;
    }

    /** Move a claim to another status, if its current one allows it; refused otherwise. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!claim.getStatus().canMoveTo(status)) {
            throw new ClaimStatusException(id, claim.getStatus(), status);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        claim.setStatus(status);
        // Settling fixes the due at the rate the person's grade has now; with no rate it stays live.
        String due = "";
        if (status == ClaimStatus.SETTLED) {
            BigDecimal settledDue = rates.findBySheetIdAndGrade(sheetId, person.getGrade())
                    .map(Rate::getPct)
                    .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_UP))
                    .orElse(null);
            claim.setSettledDue(settledDue);
            due = " due=" + (settledDue == null ? "n/a" : settledDue.toPlainString());
        }
        // The CLAIM_VOIDED record carries the number the claim had.
        Long number = claim.getNumber();
        if (status == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
        } else if (status == ClaimStatus.VOID) {
            claim.setNumber(null);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (status) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, user, person, saved) + numberOf(number) + due);
        return saved;
    }

    /** The amount as shown (two decimals) and the date as yyyy-mm-dd. */
    private static String record(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " date=" + claim.getDate();
    }

    /** The claim's number at the end of an audit record, or nothing when it has none. */
    private static String numberOf(Long number) {
        return number == null ? "" : " number=" + number;
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
