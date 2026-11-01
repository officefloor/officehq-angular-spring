package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claims on a sheet: listed newest first, added to a person and edited one at a time. A claim
 * given no date takes the sheet's today. A claim is added as DRAFT and moves through its statuses
 * as {@link ClaimStatus} allows; a change it does not allow is refused. Opening a claim gives it
 * the sheet's next number; voiding it clears the number, which is never given again. Settling it
 * fixes its due at its person's grade rate of the moment on the claim's date; with no such rate it
 * stays live. The user who opened a claim cannot settle it. No claim dated inside a closed period
 * can be added, edited (to or from such a date), opened, settled or voided.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            RollupService rollup, PeriodRepository periods, Audit audit) {
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = dateOrToday(sheetId, date);
        refuseIfClosed(sheetId, on);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note));
        record("CLAIM_ADDED", sheetId, user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim edit(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = dateOrToday(sheetId, date);
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, on);
        claim.setAmount(amount);
        claim.setDate(on);
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus next) {
        // Opening takes the sheet's next number, locking the sheet first so the claim is read only
        // once no other open on the sheet is under way; a refusal rolls the number back.
        Long opened = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().canBecome(next)) {
            throw new ClaimStatusRefusedException();
        }
        if (next == ClaimStatus.SETTLED && user.strip().equals(claim.getOpenedBy())) {
            throw new ClaimSettledByOpenerException();
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        refuseIfClosed(sheetId, claim.getDate());
        Long number = claim.getNumber();
        switch (next) {
            case OPEN -> {
                number = opened;
                claim.setNumber(number);
                claim.setOpenedBy(user.strip());
            }
            case SETTLED -> claim.setSettledDue(dueOf(sheetId, person, claim));
            case VOID -> claim.setNumber(null);
            default -> {}
        }
        claim.setStatus(next);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String due = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString())
                : "";
        record(event, sheetId, user, person, saved, number, due);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A claim's figure now: the due fixed when it was settled, else the due bound by its person's
     * last statement, else its live due; null when it has none.
     */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isStated()) return claim.getStatedDue();
        return dueOf(sheetId, person, claim);
    }

    /** A claim's amount times its person's grade rate on its date, rounded half to even to cents; null with no rate. */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        return rates.on(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    /** A claim's audit line; one about a claim with a number has it, then any extra fields. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, Long number,
            String extra) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip() + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + extra);
    }

    /** Refuse a change to a claim dated inside a closed period; the caller holds the sheet's lock. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new PeriodClosedException();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
