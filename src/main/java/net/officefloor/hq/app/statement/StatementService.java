package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
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
    private final Dues dues;
    private final SheetService sheets;
    private final Rollup rollup;
    private final RoleService roles;
    private final History history;
    private final Audit audit;
    private final PoolService pools;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            Dues dues, SheetService sheets, Rollup rollup, RoleService roles, History history, Audit audit,
            PoolService pools) {
        this.pools = pools;
        this.history = history;
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.dues = dues;
        this.sheets = sheets;
        this.rollup = rollup;
        this.roles = roles;
        this.audit = audit;
    }

    /** What each person's statements on the sheet come to, by person id; a person with none is
     *  missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> summaries(String sheetId) {
        return statements.findBySheetIdAndCancelledOnIsNullOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    /** What each person's statements on the sheet came to at the end of a day: those taken while the
     *  sheet's today was on or before it and not cancelled by then, by person id; a person with none
     *  is missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> summaries(String sheetId, LocalDate day) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> s.stoodOn(day))
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    /** What one person's statements come to. */
    @Transactional(readOnly = true)
    public StatementSummary summary(String sheetId, long personId) {
        return StatementSummary.of(statements.findBySheetIdAndPersonIdAndCancelledOnIsNullOrderByIdAsc(sheetId, personId));
    }

    /** Take a statement of a person: record their due-total now and bind each of their claims that
     *  counts in the live totals to its due as shown now, late charge included (a figure an earlier
     *  statement bound stays as it is); any other
     *  claim (one that does not count, a deferred one, or an open unbound one dated within one of their
     *  holds) is unbound. */
    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        return take(sheetId, user, personId, null);
    }

    /** Take a statement of a person as of a day (null: the sheet's today) on or before the sheet's
     *  today: the sheet as it stood at the end of that day (see {@link History}) stands in for it as
     *  it is now, that day for its today. Their due-total then is recorded, and each claim of theirs
     *  that counted in the live totals then is bound to the due it showed then, late charge included; every other claim
     *  of theirs is unbound. Refused when the day is after the sheet's today, or when the person was
     *  not on the sheet yet then. */
    @Transactional
    public Taken take(String sheetId, String user, long personId, LocalDate asOf) {
        LocalDate today = sheets.today(sheetId);
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A statement cannot be taken as of a day after the sheet's today");
        }
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        History.SheetAsOf then = asOf == null ? null : history.asOf(sheetId, asOf);
        Person was = then == null ? person : then.person(personId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The person was not on the sheet yet on that day"));
        List<Claim> bound = rollup.tracking(sheetId, user, () -> {
            List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId);
            Dues.Basis basis = then == null ? dues.now(sheetId) : then.basis();
            List<Claim> counted = Dues.live(was, then == null ? theirs : then.claimsOf(personId), basis.today());
            // their parts of claims shared with them count, and the parts others hold of theirs do not
            Map<Long, Person> everyone = then != null ? then.people()
                    : people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                            .collect(Collectors.toMap(Person::getId, p -> p));
            Map<Long, List<Claim>> byPerson = (then != null ? then.claims()
                    : claims.findBySheetIdOrderByListedDesc(sheetId)).stream()
                    .collect(Collectors.groupingBy(Claim::getPersonId));
            Map<Long, Person> holders = everyone;
            List<BigDecimal> held = new ArrayList<>();
            for (Person other : everyone.values()) {
                for (Claim claim : Dues.live(other, byPerson.getOrDefault(other.getId(), List.of()), basis.today())) {
                    if (!claim.getShares().isEmpty()) {
                        BigDecimal part = dues.parts(basis, claim, dues.figure(basis, other, claim), holders).get(personId);
                        if (part != null) {
                            held.add(part);
                        }
                    }
                }
            }
            BigDecimal dueTotal = dues.dueTotal(basis, was, counted, holders, held,
                    pools.cutTotals(sheetId, asOf).getOrDefault(personId, BigDecimal.ZERO.setScale(2)));
            Map<Long, Claim> countedById = counted.stream().collect(Collectors.toMap(Claim::getId, c -> c));
            for (Claim claim : theirs) {
                Claim figured = countedById.get(claim.getId());
                if (figured != null) {
                    claim.bind(true, dues.bindable(basis, was, figured), dues.bindsNa(basis, was, figured));
                } else {
                    claim.bind(false, null);
                }
            }
            claims.saveAllAndFlush(theirs);
            statements.saveAndFlush(new Statement(sheetId, personId, today, user, dueTotal, counted.size(), asOf));
            audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                    + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.setScale(2).toPlainString())
                    + " claims=" + counted.size() + (asOf != null ? " asof=" + asOf : ""));
            return theirs;
        });
        return new Taken(person, summary(sheetId, personId), bound);
    }

    /** Cancel a person's last statement: only a steward may, and only when one was taken. The claims
     *  it bound become live again (a figure an earlier statement bound is not restored), and the
     *  statement before it, if any, becomes their last. The cancelled statement is kept, marked with
     *  the sheet's today, so the sheet as of an earlier day still shows it. Their last is their own
     *  last statement, or, having none, the last one carried to them by a merge or a transfer. */
    @Transactional
    public Taken cancelLast(String sheetId, String user, long personId) {
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can cancel a statement");
        }
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        List<Statement> taken = statements.findBySheetIdAndPersonIdAndCancelledOnIsNullOrderByIdAsc(sheetId, personId);
        if (taken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No statement has been taken of this person");
        }
        Statement last = StatementSummary.last(taken);
        List<Claim> unbound = rollup.tracking(sheetId, user, () -> {
            List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId);
            theirs.forEach(claim -> claim.bind(false, null));
            claims.saveAllAndFlush(theirs);
            last.cancel(sheets.today(sheetId));
            statements.saveAndFlush(last);
            audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                    + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().setScale(2).toPlainString()));
            return theirs;
        });
        return new Taken(person, summary(sheetId, personId), unbound);
    }
}
