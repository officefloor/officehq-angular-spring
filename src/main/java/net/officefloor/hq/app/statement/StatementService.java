package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken of the people on each sheet. Taking one binds each of the person's claims that
 * counts (OPEN, PENDING or SETTLED, but not an OPEN one deferred on the sheet's today, nor an unbound OPEN or PENDING one dated within one of their holds) to the figure it shows then, and leaves
 * each that does not count unbound;
 * it records the person's due-total then: the sum of what is due on those claims as each shows it, late
 * charges included (a claim with no figure is skipped), at most the sheet's cap. A late charge is never bound: it keeps following
 * the sheet's today.
 * <p>
 * A steward can cancel a person's last statement: the claims it bound become live again (a figure a
 * statement before it bound is not restored), and the person's statement before it is their last again.
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
    private final SettingsService settings;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, SheetService sheets, Audit audit, RollupService rollup,
            SettingsService settings) {
        this.settings = settings;
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
        LocalDate today = sheets.today(sheetId);
        List<Claim> counting = new ArrayList<>();
        for (Claim claim : theirs) {
            if (RollupService.countsLive(claim, person, today)) counting.add(claim);
        }
        int counted = counting.size();
        // The person's due-total as shown (the sum of those claims' figures, at most the sheet's cap): n/a
        // when none of their claims has a figure and no rate applies.
        String shown = before.people().get(personId).dueTotal();
        BigDecimal dueTotal = shown.equals(RollupService.NONE) ? null : new BigDecimal(shown);
        Statement statement = statements.saveAndFlush(
                new Statement(sheetId, personId, sheets.today(sheetId), dueTotal, counted));
        for (Claim claim : theirs) {
            if (counting.contains(claim)) claim.bind(claimService.figureOf(claim, person), statement.getId());
            else claim.unbind();
        }
        claims.flush();
        audit.change("STATEMENT", sheetId, user, "name=" + person.getName(),
                "due-total=" + (dueTotal == null ? RollupService.NONE : dueTotal.toPlainString()), "claims=" + counted);
        rollup.recordSince(sheetId, user, before);
        return new Taken(statement, theirs);
    }

    /** Cancel the person's last statement: the claims it bound become live again. Refused when the user is
     * not a steward of the sheet, and when the person has no statement. */
    @Transactional
    public Taken cancelLast(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new StatementNotStewardException();
        List<Claim> theirs = claims.lockBySheetIdAndPersonId(sheetId, personId);
        Statement last = statements.findFirstBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)
                .orElseThrow(StatementNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        for (Claim claim : theirs) {
            if (claim.isStated() && last.getId().equals(claim.getStatedBy())) claim.unbind();
        }
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.change("STATEMENT_CANCELLED", sheetId, user, "name=" + person.getName(), "due-total="
                + (last.getDueTotal() == null ? RollupService.NONE : last.getDueTotal().toPlainString()));
        rollup.recordSince(sheetId, user, before);
        return new Taken(last, theirs);
    }
}
