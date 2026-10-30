package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The statements taken for the people on a sheet. Taking one records the person's due-total and
 * binds each of their claims that counts to the figure it has then (without its late charge, which
 * keeps following the sheet's today); a claim that does not count is released to its live figure.
 * A steward can cancel a person's last statement: the claims it bound are released to their live
 * figures (a figure an earlier statement bound is not restored).
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateService rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            RateService rates, SheetService sheets, Rollup rollup, AccessService access,
            ApplicationEventPublisher events) {
        this.access = access;
        this.rollup = rollup;
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
        Rollup.Figures before = rollup.figures(sheetId);
        RateTable table = rates.table(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal dueTotal = Dues.total(person::gradeOn, theirs, table, person.leaves());
        int counting = 0;
        for (Claim claim : theirs) {
            if (Dues.counts(claim, person.leaves(), table.today())) {
                claim.bind(Dues.base(claim, person::gradeOn, table));
                counting++;
            } else {
                claim.unbind();
            }
        }
        claims.flush();
        statements.saveAndFlush(new Statement(sheetId, personId, sheets.today(sheetId), dueTotal, counting));
        events.publishEvent(new StatementTaken(sheetId, user, person.getName(), dueTotal, counting));
        rollup.changed(sheetId, user, before);
    }

    /** Cancel the person's last statement; only a steward may, and only while the person has one. */
    @Transactional
    public void cancel(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can cancel a statement");
        }
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        if (taken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person has no statement to cancel");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Statement last = taken.get(taken.size() - 1);
        for (Claim claim : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            if (claim.isStated()) {
                claim.unbind();
            }
        }
        claims.flush();
        statements.delete(last);
        statements.flush();
        events.publishEvent(new StatementCancelled(sheetId, user, person.getName(), last.getDueTotal()));
        rollup.changed(sheetId, user, before);
    }
}
