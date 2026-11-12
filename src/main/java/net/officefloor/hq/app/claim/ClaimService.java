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

/** Keeps the people's claims on each sheet. A claim with no date is dated the sheet's today. */
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
        sheets.today(sheetId);
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount.setScale(2), on, note));
        audit("CLAIM_ADDED", claim, person, user);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        claim.setAmount(amount.setScale(2));
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note);
        Claim updated = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(updated.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        audit("CLAIM_EDITED", updated, person, user);
        return updated;
    }

    /** Move a claim to another status; refused when its current status does not allow it. Settling fixes the
     *  claim's due at its grade's rate in force on its date; with no rate then, its due stays live. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().canBecome(to)) {
            throw new ClaimStatusException(claim.getStatus(), to);
        }
        Integer number = claim.getNumber();
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(rates.findInForce(sheetId, person.getGrade(), claim.getDate())
                    .map(rate -> due(claim.getAmount(), rate))
                    .orElse(null));
        }
        claim.setStatus(to);
        claim.setNumber(to == ClaimStatus.VOID ? null : number);
        Claim updated = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit(event, updated, person, user, number, to == ClaimStatus.SETTLED
                ? " due=" + (updated.getSettledDue() == null ? "n/a" : updated.getSettledDue().toPlainString())
                : "");
        return updated;
    }

    /** An amount times a rate, divided by 100, rounded half up to cents. */
    private static BigDecimal due(BigDecimal amount, Rate rate) {
        return amount.multiply(rate.getRate()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** The amount as the screen shows it (two decimals), the date YYYY-MM-DD. */
    private void audit(String event, Claim claim, Person person, String user) {
        audit(event, claim, person, user, claim.getNumber(), "");
    }

    /** A claim with a number (or one voided while it had one) follows its date with number=, then any extra fields. */
    private void audit(String event, Claim claim, Person person, String user, Integer number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }
}
