package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken of the people on each sheet. Taking one binds each of the person's claims that
 * counts (OPEN or SETTLED, but not OPEN while they are on hold) to the figure it shows then, and leaves
 * each that does not count unbound;
 * it records the person's due-total then: the sum of what is due on those claims as each shows it, late
 * charges included (a claim with no figure is skipped). A late charge is never bound: it keeps following
 * the sheet's today.
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, SheetService sheets, Audit audit, RollupService rollup) {
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** A statement taken, with the person's claims as it left them, newest first. */
    public record Taken(Statement statement, List<Claim> claims) {
    }

    /** The sheet's statements in the order they were taken. */
    @Transactional(readOnly = true)
    public List<Statement> list(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        List<Claim> theirs = claims.lockBySheetIdAndPersonId(sheetId, personId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!RollupService.countsLive(claim, person)) {
                claim.unbind();
                continue;
            }
            BigDecimal due = claimService.shownOf(claim, person);
            claim.bind(claimService.figureOf(claim, person));
            if (due != null) total = total.add(due);
            counted++;
        }
        claims.flush();
        // The person's due-total as shown: n/a when none of their claims has a figure and no rate applies.
        String shown = before.people().get(personId).dueTotal();
        BigDecimal dueTotal = shown.equals(RollupService.NONE) ? null : total;
        Statement statement = statements.saveAndFlush(
                new Statement(sheetId, personId, sheets.today(sheetId), dueTotal, counted));
        audit.change("STATEMENT", sheetId, user, "name=" + person.getName(),
                "due-total=" + (dueTotal == null ? RollupService.NONE : dueTotal.toPlainString()), "claims=" + counted);
        rollup.recordSince(sheetId, user, before);
        return new Taken(statement, theirs);
    }
}
