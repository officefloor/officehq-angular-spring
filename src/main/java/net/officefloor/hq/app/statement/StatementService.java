package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes statements of people. A statement records the person's due-total and binds each of their counting
 * claims to the due it shows then; their other claims are left unbound, so their dues are live again.
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            RateService rates, SheetService sheets, Audit audit, RollupService rollup) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Each person's statement summary on the sheet, by person id; a person with none is missing. */
    public Map<Long, StatementSummary> summaries(String sheetId) {
        Map<Long, StatementSummary> by = new HashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            by.put(s.getPersonId(), by.getOrDefault(s.getPersonId(), StatementSummary.NONE).then(s));
        }
        return by;
    }

    public StatementSummary summary(String sheetId, Long personId) {
        StatementSummary summary = StatementSummary.NONE;
        for (Statement s : statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId)) {
            summary = summary.then(s);
        }
        return summary;
    }

    @Transactional
    public Person take(String sheetId, String user, Long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        // As on the screen: n/a when the grade has no rate today, unless a counting claim has a fixed due.
        boolean anyDue = rates.rateOn(sheetId, person.getGrade(), today).isPresent();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts()) {
                claim.setStated(false, null);
                continue;
            }
            counted++;
            if (claim.getSettledDue() != null || claim.getStatedDue() != null) {
                anyDue = true;
            }
            BigDecimal due = dueOf(sheetId, person, claim).orElse(null);
            if (due != null) {
                total = total.add(due);
            }
            claim.setStated(true, due);
        }
        claims.saveAllAndFlush(theirs);
        String dueTotal = anyDue ? total.toPlainString() : "n/a";
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /**
     * The due the claim shows now: the due fixed when it was settled, else the one its last statement bound it to,
     * else its amount times its grade's rate on its date; empty when it has none (n/a).
     */
    private Optional<BigDecimal> dueOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        if (claim.isStated()) {
            return Optional.ofNullable(claim.getStatedDue());
        }
        return rates.rateOn(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN));
    }
}
