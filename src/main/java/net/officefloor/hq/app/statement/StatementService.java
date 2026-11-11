package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
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
import net.officefloor.hq.app.sheet.Sheet;
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
        Sheet sheet = sheets.get(sheetId);
        LocalDate today = sheet.getToday();
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        // As on the screen: n/a when the grade in force today has no rate today, unless a counting claim has a fixed due
        // or a due on or before today.
        boolean anyDue = rates.rateOn(sheetId, person.gradeOn(today), today).isPresent();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!person.counts(claim, today)) {
                claim.setStated(false, null);
                continue;
            }
            counted++;
            if (claim.getSettledDue() != null || claim.getBoundDue() != null) {
                anyDue = true;
            }
            // The statement binds the figure under the claim's due; its late charge keeps following the sheet's today.
            BigDecimal figure = figureOf(sheetId, person, claim).orElse(null);
            BigDecimal due = claim.getSettledDue() != null ? figure
                    : sheet.withLateCharge(figure, claim.getStatus().isOpen() && !claim.isCorrection(),
                            claim.getDueBy(), claim.getDate());
            if (due != null) {
                total = total.add(due);
                // A due under a grade the person had on an earlier date counts too.
                if (!claim.getDate().isAfter(today)) {
                    anyDue = true;
                }
            }
            claim.setStated(true, figure);
        }
        claims.saveAllAndFlush(theirs);
        String dueTotal = anyDue ? sheet.capped(total).toPlainString() : "n/a";
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /**
     * The figure under the claim's due: the due fixed when it was settled, else the one its last statement bound it
     * to, else its amount times its grade's rate on its date raised to the sheet's floor (unless a correction); empty when it has none (n/a).
     */
    private Optional<BigDecimal> figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        if (claim.isBound()) {
            return Optional.ofNullable(claim.getStatedDue());
        }
        return rates.rateOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> sheets.get(sheetId).rateStep(claim.getAmount(), pct, claim.getRoundingDate()))
                .map(due -> claim.isCorrection() ? due : sheets.get(sheetId).floored(due, claim.getDate()));
    }
}
