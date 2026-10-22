package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollups;
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
    private final Dues dues;
    private final SheetService sheets;
    private final Rollups rollups;
    private final PeriodService periods;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollups rollups, PeriodService periods, Audit audit) {
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /**
     * Add a claim for a person on the sheet; with no date it is dated the sheet's today. Refused when
     * it is dated inside a closed period.
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        sheets.lock(sheetId);
        periods.refuseIfClosed(sheetId, dated);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, money(amount), dated, clean(note)));
        audited("CLAIM_ADDED", sheetId, user, person, claim);
        rollups.audit(sheetId, user, before);
        return claim;
    }

    /**
     * Change a claim's amount, date and note; with no date it keeps its own. Only a DRAFT claim can be
     * edited: an OPEN one is changed by voiding it and adding a new one. Refused when the claim, or the
     * date it moves to, is inside a closed period.
     */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (date != null) {
            periods.refuseIfClosed(sheetId, date);
        }
        claim.change(money(amount), date != null ? date : claim.getDate(), clean(note));
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to a new status, recording the event; refused (and nothing written) when the
     * claim's current status does not allow the move. Settling fixes the claim's due at the rate
     * the person's grade has in force on the claim's date, or at the figure a statement bound it to.
     * Opening numbers the claim and records who opened it; a voided claim keeps its number, which
     * is never reused. The user who opened a claim
     * is refused when settling it. A claim dated inside a closed period cannot be moved.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next) {
        // Opening takes the sheet's lock before reading the claim, so concurrent opens on the
        // sheet are serialised: each sees the other's committed claim and number.
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.become(next);
        if (next == ClaimStatus.OPEN) {
            claim.number(number);
            claim.openedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.settleDue(claim.isStated() ? claim.getStatedDue() : dues.live(claim, person.getGrade()));
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
        audited(event, sheetId, user, person, saved, saved.getNumber(), suffix);
        rollups.audit(sheetId, user, before);
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

    /** The due figure a claim shows (see {@link Dues#figure}). */
    public BigDecimal figure(Claim claim, String grade) {
        return dues.figure(claim, grade);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }
}
