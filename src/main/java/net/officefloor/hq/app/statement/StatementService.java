package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken for people on a sheet. Taking one records the person's due-total at that
 * moment (late charges included) and binds each of their claims that counts to the figure it has
 * then, without its late charge, which keeps following the sheet's today; their other claims
 * are left unbound, their due live again. A claim's due fixed when settled stays as it is.
 */
@Service
public class StatementService {

    /** A person's statements: how many, and the last one's due-total (null when n/a) and date. */
    public record Summary(Long personId, int count, BigDecimal lastDueTotal, LocalDate lastDate) {}

    /** A statement just taken, with the person's claims as they now stand. */
    public record Taken(Summary summary, List<Claim> claims) {}

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final SheetRepository sheets;
    private final RollupService rollup;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateService rates, SheetRepository sheets, RollupService rollup, Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Each person's statements on the sheet, for those with any. */
    @Transactional(readOnly = true)
    public List<Summary> summaries(String sheetId) {
        Map<Long, List<Statement>> byPerson = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            byPerson.computeIfAbsent(s.getPersonId(), id -> new ArrayList<>()).add(s);
        }
        return byPerson.entrySet().stream().map(e -> summaryOf(e.getKey(), e.getValue())).toList();
    }

    @Transactional
    public Taken take(String sheetId, String user, Long personId) {
        // Lock the sheet so the claims and rates read are not changed while the statement is taken.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        boolean fixed = false;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts(person.isHeld())) {
                claim.unstate();
                continue;
            }
            BigDecimal figure = claimService.figureOf(sheetId, person, claim);
            BigDecimal due = claimService.chargedFigureOf(sheetId, person, claim);
            if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) fixed = true;
            // a due under a grade they had on its date but not today counts like a fixed one
            if (due != null && !person.gradeOn(claim.getDate()).equals(person.gradeOn(today))) fixed = true;
            claim.state(figure);
            if (due != null) total = total.add(due);
            counted++;
        }
        // As on screen: n/a when their grade has no rate today and none of the counted claims has a fixed
        // due, nor a due under another grade they had on its date.
        BigDecimal dueTotal = fixed || rates.on(sheetId, person.gradeOn(today), today).isPresent() ? total : null;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, today, user.strip()));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user.strip() + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return new Taken(summaryOf(personId, statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId)),
                theirs);
    }

    private static Summary summaryOf(Long personId, List<Statement> list) {
        Statement last = list.get(list.size() - 1);
        return new Summary(personId, list.size(), last.getDueTotal(), last.getDate());
    }
}
