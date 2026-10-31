package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The statements taken for the people on a sheet. Taking one records the person's due-total and
 * binds each of their claims that counts to the figure it shows then, late charge included (so while
 * bound its late charge no longer follows the sheet's today, and a claim already bound is bound again at
 * that figure); a claim that does not count is released to its live figure. A
 * statement can be taken as of a date gone by, from the sheet as it stood at the end of that date.
 * A steward can cancel a person's last statement: the claims it bound are released to their live
 * figures (a figure an earlier statement bound is not restored).
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateService rates;
    private final Rollup rollup;
    private final AccessService access;
    private final DayEnds dayEnds;
    private final ApplicationEventPublisher events;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            RateService rates, Rollup rollup, AccessService access, DayEnds dayEnds,
            ApplicationEventPublisher events) {
        this.dayEnds = dayEnds;
        this.access = access;
        this.rollup = rollup;
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
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
        take(sheetId, user, personId, null);
    }

    /**
     * Take a statement for the person as of a date on or before the sheet's today (null: as of now). Their
     * claims that counted at the end of that date are bound to the figures they had then, with that date
     * standing in for the sheet's today, and every other claim of theirs is released; the statement is
     * dated that date and records their due-total then. A date gone by takes the sheet as it stood at the
     * end of it (see {@link DayEnds}): refused when the date is after the sheet's today, or when the person
     * was not on the sheet by then.
     */
    @Transactional
    public void take(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RateTable table = rates.table(sheetId);
        LocalDate on = asOf == null ? table.today() : asOf;
        if (on.isAfter(table.today())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A statement cannot be taken as of a date after today");
        }
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Person then;
        Map<Long, Claim> claimsThen;
        Map<Long, Dues.Parts> parts;
        if (on.isBefore(table.today())) {
            DayEnds.SheetThen sheetThen = dayEnds.asOf(sheetId, on)
                    .filter(t -> t.people().containsKey(personId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "The person was not on the sheet by that date"));
            then = sheetThen.people().get(personId);
            claimsThen = sheetThen.claimsOf(personId);
            table = sheetThen.table();
            parts = Person.parts(sheetThen.people().values(),
                    sheetThen.claims().values().stream().flatMap(c -> c.values().stream()).toList(), table);
        } else {
            then = person;
            claimsThen = new LinkedHashMap<>();
            theirs.forEach(c -> claimsThen.put(c.getId(), c));
            parts = Person.parts(people.findBySheetIdOrderByPositionAscIdAsc(sheetId),
                    claims.findBySheetIdOrderByIdDesc(sheetId), table);
        }
        Rollup.Figures before = rollup.figures(sheetId);
        BigDecimal dueTotal = Dues.total(then::gradeOn, List.copyOf(claimsThen.values()), table, then.leaves(),
                parts.getOrDefault(personId, Dues.Parts.NONE));
        int counting = 0;
        for (Claim claim : theirs) {
            Claim stood = claimsThen.get(claim.getId());
            if (stood != null && Dues.counts(stood, then.leaves(), table.today())) {
                claim.bind(Dues.figure(stood, then::gradeOn, table));
                counting++;
            } else {
                claim.unbind();
            }
        }
        claims.flush();
        statements.saveAndFlush(new Statement(sheetId, personId, on, dueTotal, counting));
        events.publishEvent(new StatementTaken(sheetId, user, person.getName(), dueTotal, counting, asOf));
        rollup.changed(sheetId, user, before);
    }

    /**
     * Carry a person's statements to the person receiving them in a merge or transfer: the receiver's count
     * becomes their own plus the other's, and the receiver's last statement stays theirs (or, having none,
     * becomes the other's). The other's statements are dropped from them.
     */
    @Transactional
    public void carry(String fromSheet, long fromPerson, String toSheet, long toPerson) {
        List<Statement> theirs = statements.findBySheetIdAndPersonIdOrderByIdAsc(fromSheet, fromPerson);
        List<Statement> own = statements.findBySheetIdAndPersonIdOrderByIdAsc(toSheet, toPerson);
        // taken in order by id: the other's come first, then the receiver's own taken again after them
        List<Statement> carried = new ArrayList<>();
        theirs.forEach(s -> carried.add(s.carried(toSheet, toPerson)));
        own.forEach(s -> carried.add(s.carried(toSheet, toPerson)));
        statements.deleteAll(theirs);
        statements.deleteAll(own);
        statements.flush();
        statements.saveAllAndFlush(carried);
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
