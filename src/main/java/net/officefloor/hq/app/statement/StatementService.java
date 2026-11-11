package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Statements on a sheet. Taking a person's statement records their due-total at that moment, taken
 * on the sheet's today (held to the sheet's cap), or as of a date on or before it, and binds every claim of theirs that counts (OPEN, PENDING or SETTLED, held or not, but not a
 * retired person's OPEN or PENDING claims) to the figure it has then and to its due as shown then, late charge included: while bound, the
 * claim shows and totals that due, its late charge no longer following the sheet's today, and the
 * next statement binds it as it stands (a SETTLED claim's frozen due is unaffected). A claim of
 * theirs that does not count is unbound, so its figure and late charge are live again. The rate
 * figure under the charge, raised to the floor, is kept too (the floor is applied before the charge, see
 * {@link net.officefloor.hq.app.claim.LateCharge}).
 * A steward can cancel a person's last statement: the claims it bound are live again (a figure bound
 * by a statement before it is not restored), and the statement before it is the last one again.
 */
@Service
public class StatementService {

    /**
     * A person's statements: how many, the last one (none before the first), and every one still
     * standing, in the order taken (a merge's or transfer's brought over before their own).
     */
    public record Summary(long personId, int count, Statement last, List<Statement> standing) {

        static Summary of(long personId, List<Statement> standing) {
            return new Summary(personId, standing.size(), standing.isEmpty() ? null : standing.get(standing.size() - 1),
                    List.copyOf(standing));
        }
    }

    /** A statement just taken, with the person's claims as it left them. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    /** A statement just cancelled: the person's statements left (none last when none are), and their claims. */
    public record Cancelled(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateChain rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateChain rates, SheetService sheets, Audit audit, RollupService rollup,
            RoleService roles) {
        this.roles = roles;
        this.rollup = rollup;
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Each person's statements on the sheet who has taken any. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, List<Statement>> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            by.computeIfAbsent(s.getPersonId(), id -> new ArrayList<>()).add(s);
        }
        return by.entrySet().stream().map(e -> Summary.of(e.getKey(), e.getValue())).toList();
    }

    /**
     * Take the person's statement now, or as of a date on or before the sheet's today (see
     * {@link #takeAsOf}). Refused (400) for a date after the sheet's today.
     */
    @Transactional
    public Taken take(String sheetId, String user, long personId, LocalDate asOf) {
        roles.refuseViewer(sheetId, user);
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate today = sheets.today(sheetId);
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A statement cannot be taken as of a date after the sheet's today.");
        }
        if (asOf != null && !person.addedBy(asOf)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " was not on the sheet by " + asOf + ".");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Tally tally = asOf == null ? takeNow(sheetId, person, theirs, today) : takeAsOf(sheetId, person, theirs, asOf);
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, asOf != null ? asOf : today,
                tally.dueTotal(), tally.counted(), today));
        List<Statement> standing = statements.findBySheetIdAndPerson(sheetId, personId);
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + money(tally.dueTotal()) + " claims=" + tally.counted()
                + (asOf != null ? " asof=" + asOf : ""));
        rollup.recordChanges(sheetId, user, before);
        return new Taken(Summary.of(personId, standing), theirs);
    }

    /** What a statement found: the person's due-total (none for n/a) and how many claims counted. */
    private record Tally(BigDecimal dueTotal, int counted) {
    }

    /** Bind the person's claims that count now to their figures now. */
    private Tally takeNow(String sheetId, Person person, List<Claim> theirs, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFigure = false;
        int counted = 0;
        List<LocalDate> countedDates = new ArrayList<>();
        for (Claim claim : theirs) {
            if (person.counts(claim, today)) {
                countedDates.add(claim.getDate());
                BigDecimal figure = claimService.figure(sheetId, person, claim);
                if (claim.getStatus() != ClaimStatus.SETTLED) {
                    figure = LateCharge.floored(sheets.sheet(sheetId), claim, figure);
                }
                BigDecimal charged = claimService.charged(sheetId, person, claim);
                claim.bind(true, figure, charged);
                if (charged != null) {
                    total = total.add(charged);
                    anyFigure = true;
                }
                counted++;
            } else {
                claim.bind(false, null);
            }
        }
        // the parts they hold of others' claims count towards their due-total, as on screen
        Optional<BigDecimal> held = rollup.heldPart(sheetId, person.getId());
        if (held.isPresent()) {
            total = total.add(held.get());
            anyFigure = true;
        }
        // as on screen: n/a when the grade has no rate today and no claim that counts has a figure
        BigDecimal dueTotal = anyFigure || rates.findInForce(sheetId, person.gradeOn(today), today).isPresent() ? sheets.capped(sheetId, person.getId(), total, countedDates) : null;
        return new Tally(dueTotal, counted);
    }

    /**
     * Bind the person's claims that counted at the end of a day to their figures as they stood then:
     * every change made while the sheet's today was on or before the day counts, every later one
     * does not, and the day stands in for the sheet's today in every figure (the grade and rate in
     * force, the floor, late charges, holds, retirements, deferrals and the cap). A claim's status
     * then is the one its status log records; a claim with none by then did not count. A claim
     * bound by the last statement counts as bound then only when that statement was taken by then.
     * Every other claim of theirs is unbound.
     */
    private Tally takeAsOf(String sheetId, Person person, List<Claim> theirs, LocalDate day) {
        Sheet then = sheets.sheet(sheetId).asOf(day);
        List<List<Rate>> ratesThen = rates.asOf(sheetId, day);
        boolean boundThen = statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> Objects.equals(s.getPersonId(), person.getId())).reduce((first, last) -> last)
                .map(last -> last.madeBy(day)).orElse(false);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFigure = false;
        int counted = 0;
        List<LocalDate> countedDates = new ArrayList<>();
        for (Claim claim : theirs) {
            ClaimStatus status = claim.statusAt(day).orElse(null);
            boolean stated = boundThen && claim.isStated();
            if (status == null || !person.countedAsOf(claim, status, day, stated)) {
                claim.bind(false, null);
                continue;
            }
            BigDecimal figure;
            BigDecimal charged;
            if (status == ClaimStatus.SETTLED) {
                figure = claim.settledDueAt(day);
                charged = figure;
            } else {
                figure = stated ? claim.getStatedDue()
                        : LateCharge.floored(then, claim,
                                RateChain.inForce(ratesThen, person.gradeOn(claim.getDate(), day), claim)
                                        .map(Rate::getPct)
                                        .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2,
                                                Rounding.mode(then, Rounding.dateOf(claim, theirs))))
                                        .orElse(null));
                charged = stated && claim.getStatedCharged() != null ? claim.getStatedCharged()
                        : LateCharge.withCharge(then, claim, person.gradeOn(claim.getDate(), day), figure,
                                status.isOpen() && !claim.isDeferredAsOf(status, day, stated));
            }
            claim.bind(true, figure, status.isOpen() ? charged : null);
            countedDates.add(claim.getDate());
            if (charged != null) {
                total = total.add(charged);
                anyFigure = true;
            }
            counted++;
        }
        // as on screen: n/a when the grade then has no rate then and no claim that counted has a figure
        boolean none = !anyFigure && RateChain.inForce(ratesThen, person.gradeOn(day, day), day).isEmpty();
        return new Tally(none ? null : then.capped(total, null, then.capDate(countedDates.stream())), counted);
    }

    /**
     * Cancel the person's last statement: each claim it bound is unbound, so its figure is live
     * again. Refused (403) to anyone but a steward, and (409) for a person with no statement.
     */
    @Transactional
    public Cancelled cancel(String sheetId, String user, long personId) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        List<Statement> theirStatements = statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> Objects.equals(s.getPersonId(), personId)).toList();
        if (theirStatements.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " has no statement to cancel.");
        }
        Statement last = theirStatements.get(theirStatements.size() - 1);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        for (Claim claim : theirs) {
            if (claim.isStated()) {
                claim.bind(false, null);
            }
        }
        claims.saveAllAndFlush(theirs);
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + money(last.getDueTotal()));
        rollup.recordChanges(sheetId, user, before);
        return new Cancelled(Summary.of(personId, theirStatements.subList(0, theirStatements.size() - 1)), theirs);
    }

    public static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
