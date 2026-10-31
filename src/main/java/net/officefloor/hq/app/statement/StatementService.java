package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The statements taken of people on a sheet. */
@Service
public class StatementService {

    /** What a person's statements come to: how many, and the last one's due-total and date. */
    public record Summary(long personId, int count, String dueTotal, LocalDate takenOn) {
    }

    /** A statement just taken: the person's summary and their claims, newest first, as it left them. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;
    private final Rollup rollup;
    private final RoleService roles;
    private final SheetHistory history;

    public StatementService(StatementRepository statements, ClaimRepository claims, PersonRepository people,
            RateRepository rates, SheetService sheets, SheetRepository sheetStore, Audit audit, Rollup rollup,
            RoleService roles, SheetHistory history) {
        this.roles = roles;
        this.history = history;
        this.statements = statements;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The summary of each person on the sheet who has had a statement taken. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        return summarize(statements.findBySheetIdOrderByIdAsc(sheetId));
    }

    /** The summary of each person who has had one of these statements (in the order taken) taken. */
    public static List<Summary> summarize(List<Statement> taken) {
        Map<Long, Summary> byPerson = new LinkedHashMap<>();
        for (Statement s : taken) {
            Summary before = byPerson.get(s.getPersonId());
            byPerson.put(s.getPersonId(),
                    new Summary(s.getPersonId(), before == null ? 1 : before.count() + 1, s.getDueTotal(), s.getTakenOn()));
        }
        return List.copyOf(byPerson.values());
    }

    /**
     * Take a statement of a person: record their due-total now (with the late charges their overdue claims have gained), bind each of their claims that counts
     * to the figure it has now (its settled figure, else the figure it is bound to, else its amount at
     * its grade's rate in force on its date, not raised to the floor) and leave the rest unbound. A late charge is never
     * bound: it keeps following the sheet's today, and the floor is applied last, to the figure with its charge. The sheet is locked first, so
     * a statement is not taken while a claim on it changes.
     * <p>
     * Taken as of a date ({@code asOf}, on or before the sheet's today), it reads the sheet as it stood at the end of
     * that date (see {@link SheetHistory}): the claims that counted then are bound to their figures as they stood
     * then, that date standing in for the sheet's today in every figure, and the rest are unbound; the statement is
     * dated that date. Refused for a date after the sheet's today, and for a person not on the sheet by then.
     */
    @Transactional
    public Taken take(String sheetId, String user, long personId, LocalDate asOf) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        LocalDate today = sheet.getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A statement can be taken as of a date on or before " + today);
        }
        Rollup.Figures before = rollup.of(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, personId);
        Stated stated;
        if (asOf == null) {
            stated = bind(sheet, rates.findBySheetId(sheetId), person, theirs, Function.identity(),
                    person::countsUnbound, theirs);
        } else {
            SheetState then = history.asOf(sheetId, asOf).orElse(null);
            SheetState.PersonState personThen = then == null ? null : then.person(personId).orElse(null);
            if (personThen == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        person.getName() + " was not on the sheet by " + asOf);
            }
            Person asThen = personThen.rebuild(sheetId);
            Map<Long, Claim> claimsThen = new HashMap<>();
            then.claims().stream().filter(c -> c.personId() == personId)
                    .forEach(c -> claimsThen.put(c.id(), c.rebuild(sheetId)));
            stated = bind(then.rebuildSheet(sheet, asOf), then.rebuildRates(sheetId), asThen, theirs,
                    c -> claimsThen.get(c.getId()), asThen::counts, claimsThen.values());
        }
        LocalDate takenOn = asOf != null ? asOf : today;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, stated.dueTotal(), takenOn));
        audit.recordChangeOnCommit(sheetId, user, "STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + stated.dueTotal() + " claims=" + stated.counted() + (asOf == null ? "" : " asof=" + asOf));
        rollup.recordChanges(sheetId, user, before);
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        return new Taken(new Summary(personId, taken.size(), stated.dueTotal(), takenOn), theirs);
    }

    /** What a statement came to: the due-total it records and how many claims it bound. */
    private record Stated(String dueTotal, int counted) {
    }

    /**
     * Bind each of the person's claims ({@code theirs}, as they are now) that counts on the {@code sheet}'s today to its
     * figure and unbind the rest, reading each as it stands on the sheet given ({@code asThen}; none when it was not
     * the person's then), with the person, the rates and the claims that decide its rounding as they stand there.
     */
    private static Stated bind(Sheet sheet, List<Rate> sheetRates, Person person, List<Claim> theirs,
            Function<Claim, Claim> asThen, BiPredicate<Claim, LocalDate> counts, Collection<Claim> roundingClaims) {
        LocalDate today = sheet.getToday();
        Set<String> grades = new HashSet<>(Set.of(person.gradeOn(today)));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyDue = false;
        int counted = 0;
        for (Claim claim : theirs) {
            Claim then = asThen.apply(claim);
            if (then == null || !counts.test(then, today) || then.isDeferredOn(today)) {
                claim.unbind();
                continue;
            }
            BigDecimal fixed = then.getSettledDue() != null ? then.getSettledDue() : then.getBoundDue();
            BigDecimal figure = fixed != null || then.isBound() ? fixed
                    : Rate.inForceOn(ratesOf(sheetRates, person.gradeOn(then.getDate())), then.getDate())
                            .map(rate -> ClaimService.liveFigure(then, rate.getRate(),
                                    sheet.roundingFor(ClaimService.roundingDate(then, roundingClaims))))
                            .orElse(null);
            anyDue |= figure != null;
            grades.add(person.gradeOn(then.getDate()));
            total = figure == null ? total
                    : total.add(then.getSettledDue() != null ? figure
                            : ClaimService.floored(then, figure.add(ClaimService.lateCharge(then, figure, sheet)), sheet));
            claim.bind(figure);
            counted++;
        }
        // As on screen: with no rate in force today for their grade on the sheet's today, nor for the grade any
        // of their claims that count is at, and no claim that has a due, the person has no due-total.
        boolean anyRate = grades.stream().anyMatch(g -> Rate.inForceOn(ratesOf(sheetRates, g), today).isPresent());
        return new Stated(!anyRate && !anyDue ? "n/a" : sheet.capped(total).toPlainString(), counted);
    }

    /**
     * A steward cancels a person's last statement: it is removed, and the claims it bound are live again. As each
     * statement binds or unbinds every claim of its person, those are the person's stated claims; a figure a statement
     * before it bound is not restored. Refused for anyone but a steward and for a person with no statement.
     */
    @Transactional
    public Taken cancel(String sheetId, String user, long personId) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward cancels a statement");
        }
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        if (taken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " has no statement to cancel");
        }
        Rollup.Figures before = rollup.of(sheetId);
        Statement last = taken.get(taken.size() - 1);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDescIdDesc(sheetId, personId);
        theirs.forEach(Claim::unbind);
        claims.saveAllAndFlush(theirs);
        statements.delete(last);
        statements.flush();
        audit.recordChangeOnCommit(sheetId, user, "STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name="
                + person.getName() + " due-total=" + last.getDueTotal());
        rollup.recordChanges(sheetId, user, before);
        List<Statement> left = taken.subList(0, taken.size() - 1);
        Statement previous = left.isEmpty() ? null : left.get(left.size() - 1);
        return new Taken(new Summary(personId, left.size(), previous == null ? null : previous.getDueTotal(),
                previous == null ? null : previous.getTakenOn()), theirs);
    }

    private static List<Rate> ratesOf(List<Rate> sheetRates, String grade) {
        return sheetRates.stream().filter(r -> r.getGrade().equals(grade)).toList();
    }
}
