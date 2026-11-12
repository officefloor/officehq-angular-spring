package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.StatementRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
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
    private final SheetRepository sheetRows;
    private final PeriodRepository periods;
    private final StatementRepository statements;
    private final Rollup rollup;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            SheetRepository sheetRows, PeriodRepository periods, StatementRepository statements, Rollup rollup,
            Audit audit) {
        this.sheetRows = sheetRows;
        this.periods = periods;
        this.rollup = rollup;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.statements = statements;
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
        refuseClosed(sheetId, on);
        Rollup.Snapshot before = rollup.take(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount.setScale(2), on, note));
        audit("CLAIM_ADDED", claim, person, user);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setAmount(amount.setScale(2));
        claim.setDate(on);
        claim.setNote(note);
        Claim updated = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(updated.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        audit("CLAIM_EDITED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Move a claim to another status; refused when its current status does not allow it,
     *  or when the user settling it is the one who opened it. Settling fixes the
     *  claim's due at its grade's rate in force on its date (a claim bound by a statement at the figure it
     *  bound); with no figure then, its due stays live. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(to)) {
            throw new ClaimStatusException(claim.getStatus(), to);
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettlerException();
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Integer number = claim.getNumber();
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setOpenedBy(user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(claim.isStated() ? claim.getStatedDue() : liveDue(claim, person));
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
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** A person, their statements in the order taken, and their claims newest first. */
    public record Stated(Person person, List<Statement> statements, List<Claim> claims) {
    }

    /** Take a person's statement: record their due-total now and how many claims count, and bind each
     *  claim that counts to the figure it has now; a claim that does not count is left unbound. */
    @Transactional
    public Stated statement(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findForUpdateBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Rollup.Snapshot before = rollup.take(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts()) {
                claim.setStated(false, null);
                continue;
            }
            BigDecimal due = due(claim, person);
            if (due != null) {
                total = total.add(due);
                figure |= claim.getSettledDue() != null || claim.isStated();
            }
            claim.setStated(true, due);
            counted++;
        }
        boolean rated = rates.findInForce(sheetId, person.getGrade(), today).isPresent();
        BigDecimal dueTotal = rated || figure ? total : null;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return new Stated(person, statements.findByPersonIdOrderByIdAsc(personId), theirs);
    }

    /** Refuse a change to a claim dated inside a closed period. Locks the sheet first, so a period being
     *  closed at the same moment is seen. */
    private void refuseClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.closedOn(sheetId, date)) {
            throw new PeriodClosedException();
        }
    }

    /** A claim's figure: the due fixed when settled, else the one its last statement bound, else its live
     *  due; none when that is n/a. */
    private BigDecimal due(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : liveDue(claim, person);
    }

    /** The claim's amount at its person's grade's rate in force on its date; none when no rate applies. */
    private BigDecimal liveDue(Claim claim, Person person) {
        return rates.findInForce(claim.getSheetId(), person.getGrade(), claim.getDate())
                .map(rate -> due(claim.getAmount(), rate))
                .orElse(null);
    }

    /** An amount times a rate, divided by 100, rounded half to even to cents. */
    private static BigDecimal due(BigDecimal amount, Rate rate) {
        return amount.multiply(rate.getRate()).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
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
