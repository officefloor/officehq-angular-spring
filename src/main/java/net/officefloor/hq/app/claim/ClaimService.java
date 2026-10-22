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

/** The claims people on a sheet make: listed, added, edited and moved through their statuses. */
@Service
public class ClaimService {

    /** Shown in place of a due when the grade has no rate. */
    private static final String NO_RATE = "n/a";

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

    /** Add a claim for a person on the sheet; with no date it is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, money(amount), dated, clean(note)));
        audited("CLAIM_ADDED", sheetId, user, person, claim);
        return claim;
    }

    /** Change a claim's amount, date and note; with no date it keeps its own. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claim(sheetId, claimId);
        claim.change(money(amount), date != null ? date : claim.getDate(), clean(note));
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        return saved;
    }

    /**
     * Move a claim to a new status, recording the event; refused (and nothing written) when the
     * claim's current status does not allow the move. Settling fixes the claim's due at the rate
     * the person's grade has in force on the claim's date. Opening records who opened the claim, and
     * that user is refused when settling it.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next) {
        // Opening takes the sheet's lock before reading the claim, so concurrent opens on the
        // sheet are serialised: each sees the other's committed claim and number.
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claim(sheetId, claimId);
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot settle it.");
        }
        Integer had = claim.getNumber();
        Person person = person(sheetId, claim.getPersonId());
        claim.become(next);
        if (next == ClaimStatus.OPEN || next == ClaimStatus.VOID) {
            claim.number(number);
        }
        if (next == ClaimStatus.OPEN) {
            claim.openedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.settleDue(rates.findInForce(sheetId, person.getGrade(), claim.getDate())
                    .map(rate -> due(claim.getAmount(), rate))
                    .orElse(null));
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : "";
        audited(event, sheetId, user, person, saved, next == ClaimStatus.VOID ? had : saved.getNumber(), suffix);
        return saved;
    }

    private Claim claim(String sheetId, long claimId) {
        return claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    /** Record the change to the claim once it is committed; a claim with a number carries it. */
    private void audited(String event, String sheetId, String user, Person person, Claim claim) {
        audited(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    private void audited(String event, String sheetId, String user, Person person, Claim claim, Integer number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /** What is due on an amount at a rate: the amount times the percent over 100, rounded half to even. */
    private static BigDecimal due(BigDecimal amount, Rate rate) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }
}
