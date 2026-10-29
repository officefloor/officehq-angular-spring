package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The statements taken for the people on a sheet. */
@Service
public class StatementService {

    /** A person's statements still standing, in their order. */
    public record Summary(Long personId, List<Statement> statements) {

        /** How many statements stand. */
        public int count() {
            return statements.size();
        }

        /** The last statement; null with none. */
        public Statement last() {
            return statements.isEmpty() ? null : statements.get(statements.size() - 1);
        }
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateChain rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingsService settings;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateChain rates, SheetService sheets, RollupService rollup,
            SettingsService settings, Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** Each person's statements on the sheet, for the people who have any. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, List<Statement>> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdInOrder(sheetId)) {
            by.computeIfAbsent(s.getPersonId(), k -> new ArrayList<>()).add(s);
        }
        return by.entrySet().stream().map(e -> new Summary(e.getKey(), e.getValue())).toList();
    }

    /**
     * Takes a statement for the person, now (as of null) or as of a date on or before the sheet's today: see
     * {@link #take(String, String, Long)} and {@link #takeAsOf}.
     */
    @Transactional
    public Summary take(String by, String sheetId, Long personId, LocalDate asOf) {
        return asOf == null ? take(by, sheetId, personId) : takeAsOf(by, sheetId, personId, asOf);
    }

    /**
     * Takes a statement for the person: records their due-total now (late charges included) and binds each of their claims that counts to
     * the figure it has now; a claim that does not count (as an open one dated within a hold, or a deferred one) is freed (live again).
     */
    @Transactional
    public Summary take(String by, String sheetId, Long personId) {
        Person person = people.findWithLockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Sheet sheet = sheets.load(sheetId);
        LocalDate today = sheet.getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean kept = false;
        // the grades whose rate today decides n/a: the one in force today and each counted claim's
        Set<String> grades = new HashSet<>();
        grades.add(person.gradeOn(today));
        int count = 0;
        for (Claim c : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            if (!person.countsLive(c, today)) {
                c.unbind();
                continue;
            }
            // a settled or already bound claim keeps its figure even with no rate in force today
            grades.add(person.gradeOn(c.getDate()));
            kept |= c.getSettledDue() != null || (c.isBound() && c.getStatedDue() != null);
            // the statement binds the rate figure under the late charge and the floor; both keep following the sheet
            BigDecimal due = claimService.due(sheetId, person, c);
            c.bind(claimService.figure(sheetId, person, c));
            count++;
            if (due != null) {
                kept = true;
                total = total.add(due);
            }
        }
        claims.flush();
        // as on screen: with no rate in force today for any of those grades and no claim with a figure, the due-total
        // is n/a
        boolean current = grades.stream().anyMatch(g -> rates.findInForce(sheetId, g, today).isPresent());
        // held to the sheet's cap, as on screen
        BigDecimal dueTotal = current || kept ? sheet.capped(total) : null;
        Statement taken = statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, count, today, null));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + count);
        rollup.recordChanges(by, sheetId, before);
        return new Summary(personId, statements.findBySheetIdAndPersonIdInOrder(sheetId, personId));
    }

    /**
     * Takes a statement for the person as of a date on or before the sheet's today. Their claims that counted at the
     * end of that date are bound to their figures as they stood then: only the changes made while the sheet's today
     * was on or before the date count (each claim's status, the rates, grades, settings, holds and retirement, and
     * the binding of the person's last statement), and the date stands in for the sheet's today in every figure. Each
     * other claim of theirs is freed, as a statement does. Refused for a date after the sheet's today, and for a
     * person added after it.
     */
    private Summary takeAsOf(String by, String sheetId, Long personId, LocalDate asOf) {
        Person current = people.findWithLockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Sheet sheet = sheets.load(sheetId);
        LocalDate today = sheet.getToday();
        if (asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A statement cannot be taken as of a date after the sheet's today.");
        }
        if (!current.addedBy(asOf)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person was not on the sheet on that date.");
        }
        Sheet then = sheet.asOf(asOf);
        Person person = current.asOf(asOf);
        List<RateChain.Tier> tiersThen = rates.tiersAsOf(sheetId, asOf);
        List<Rate> ratesThen = tiersThen.get(tiersThen.size() - 1).rates();
        // the binding of the person's last statement counts when it was taken while the sheet's today was on or before
        List<Statement> theirs = statements.findBySheetIdAndPersonIdInOrder(sheetId, personId);
        boolean bindingCounts = !theirs.isEmpty() && !theirs.get(theirs.size() - 1).getTakenOn().isAfter(asOf);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean kept = false;
        Set<String> grades = new HashSet<>();
        grades.add(person.gradeOn(asOf));
        int count = 0;
        for (Claim c : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            ClaimStatus status = c.statusAtEndOf(asOf);
            boolean bound = bindingCounts && c.isStated() && status != ClaimStatus.PENDING;
            boolean counts = status != null && status.counts() && !c.isDeferredAsOf(asOf)
                    && !(status.isOpen() && !bound && (person.getHolds().stream().anyMatch(h -> h.covers(c.getDate()))
                    || person.isRetiredOn(asOf)));
            if (!counts) {
                c.unbind();
                continue;
            }
            grades.add(person.gradeOn(c.getDate()));
            BigDecimal figure;
            BigDecimal due;
            if (status == ClaimStatus.SETTLED) {
                // its due as fixed then
                figure = c.dueLoggedAtEndOf(asOf);
                due = figure;
                kept = true;
            } else {
                figure = bound ? c.getStatedDue() : rateFigure(RateChain.forMark(tiersThen, c.getAddedMark()), then,
                        person.gradeOn(c.getDate()), c);
                kept |= bound && figure != null;
                due = ClaimService.liveDue(figure, c, person, then, status.isOpen());
            }
            c.bind(figure);
            count++;
            if (due != null) {
                kept = true;
                total = total.add(due);
            }
        }
        claims.flush();
        boolean inForce = grades.stream().anyMatch(g -> inForce(ratesThen, g, asOf) != null);
        BigDecimal dueTotal = inForce || kept ? then.capped(total) : null;
        Statement taken = statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, count, today, asOf));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + count
                + " asof=" + asOf);
        rollup.recordChanges(by, sheetId, before);
        return new Summary(personId, statements.findBySheetIdAndPersonIdInOrder(sheetId, personId));
    }

    /** The grade's rate in force on the date among the rates; null with none. */
    private static Rate inForce(List<Rate> rates, String grade, LocalDate date) {
        return Rate.inForce(rates.stream().filter(r -> r.getGrade().equals(grade)).toList(), date);
    }

    /**
     * The claim's rate figure among the rates, on the sheet as it stood: at the rate the grade has in force on its
     * date, rounded as the sheet decides for it; null (n/a) with none.
     */
    private static BigDecimal rateFigure(List<Rate> rates, Sheet sheet, String grade, Claim claim) {
        Rate rate = inForce(rates, grade, claim.getDate());
        return rate == null ? null
                : ClaimService.dueOn(claim.getAmount(), rate.getPct(), sheet.roundingOn(claim.getRoundsOn()));
    }

    /**
     * A steward cancels the person's last statement: the claims it bound are live again (a figure an earlier
     * statement bound is not restored), and the statement before it is the last. Refused unless the user is a
     * steward, and for a person with no statement. Returns the person's statements as the cancel left them.
     */
    @Transactional
    public Summary cancel(String by, String sheetId, Long personId) {
        Person person = people.findWithLockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can cancel a statement.");
        }
        List<Statement> theirs = statements.findBySheetIdAndPersonIdInOrder(sheetId, personId);
        if (theirs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no statement to cancel.");
        }
        Statement last = theirs.get(theirs.size() - 1);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        for (Claim c : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            if (c.isStated()) {
                c.unbind();
            }
        }
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(by, sheetId, before);
        return new Summary(personId, theirs.subList(0, theirs.size() - 1));
    }
}
