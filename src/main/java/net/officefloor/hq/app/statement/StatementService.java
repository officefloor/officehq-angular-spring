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
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken of the people on each sheet. Taking one binds each of the person's claims that
 * counts (OPEN or SETTLED) to the figure it shows then, and leaves each that does not count unbound;
 * it records the person's due-total then: the sum of those figures (a claim with none is skipped).
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, SheetService sheets, Audit audit) {
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
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
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts()) {
                claim.unbind();
                continue;
            }
            BigDecimal figure = claimService.figureOf(claim, person);
            claim.bind(figure);
            if (figure != null) total = total.add(figure);
            counted++;
        }
        claims.flush();
        Statement statement = statements.saveAndFlush(
                new Statement(sheetId, personId, sheets.today(sheetId), total, counted));
        audit.change("STATEMENT", sheetId, user, "name=" + person.getName(),
                "due-total=" + total.toPlainString(), "claims=" + counted);
        return new Taken(statement, theirs);
    }
}
