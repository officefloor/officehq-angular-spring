package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The statements taken for the people on a sheet. Taking one records the person's due-total and
 * binds each of their claims that counts to the figure it has then; a claim that does not count is
 * released to its live figure.
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateService rates;
    private final SheetService sheets;
    private final ApplicationEventPublisher events;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            RateService rates, SheetService sheets, ApplicationEventPublisher events) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.events = events;
    }

    /** Every person's statements on the sheet, by person. */
    @Transactional(readOnly = true)
    public Map<Long, Statements> bySheet(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), Statements::of)));
    }

    @Transactional(readOnly = true)
    public Statements forPerson(String sheetId, long personId) {
        return Statements.of(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
    }

    /** Take a statement for the person as of the sheet's today. */
    @Transactional
    public void take(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RateTable table = rates.table(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal dueTotal = Dues.total(person.getGrade(), theirs, table);
        int counting = 0;
        for (Claim claim : theirs) {
            if (claim.getStatus().counts()) {
                claim.bind(Dues.figure(claim, person.getGrade(), table));
                counting++;
            } else {
                claim.unbind();
            }
        }
        claims.flush();
        statements.saveAndFlush(new Statement(sheetId, personId, sheets.today(sheetId), dueTotal, counting));
        events.publishEvent(new StatementTaken(sheetId, user, person.getName(), dueTotal, counting));
    }
}
