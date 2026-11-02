package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The statements taken of people on a sheet. */
@Service
public class StatementService {

    /** A statement just taken: the person, what their statements now come to, and their claims
     *  (newest first) as the statement bound them. */
    public record Taken(Person person, StatementSummary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SheetService sheets;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, SheetService sheets, Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** What each person's statements on the sheet come to, by person id; a person with none is
     *  missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> summaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    /** What one person's statements come to. */
    @Transactional(readOnly = true)
    public StatementSummary summary(String sheetId, long personId) {
        return StatementSummary.of(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
    }

    /** Take a statement of a person: record their due-total now and bind each of their claims that
     *  counts to the figure it has now; a claim that does not count is unbound. */
    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal dueTotal = claimService.dueTotal(sheetId, person, theirs);
        int counted = 0;
        for (Claim claim : theirs) {
            if (claim.getStatus().counts()) {
                counted++;
                claim.bind(true, claimService.figure(sheetId, person, claim));
            } else {
                claim.bind(false, null);
            }
        }
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, today, user, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.setScale(2).toPlainString())
                + " claims=" + counted);
        return new Taken(person, summary(sheetId, personId), theirs);
    }
}
