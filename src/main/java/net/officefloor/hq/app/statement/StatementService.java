package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimBinding;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateLevels;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements taken for people on a sheet. Taking one records the person's due-total at that
 * moment (late charges included, at most the sheet's cap) and binds each of their claims that counts to the figure it has
 * then, without its late charge, which keeps following the sheet's today; their other claims
 * are left unbound, their due live again. A claim's due fixed when settled stays as it is.
 * A steward can cancel a person's last statement: the claims it bound are live again (a figure bound
 * by a statement before it is not restored) and the statement before it is their last.
 * A statement can also be taken as of an earlier date ({@link #take}); it shows that date as its date.
 */
@Service
public class StatementService {

    /** A person's statements: how many, and the last one's due-total (null when n/a) and date. */
    public record Summary(Long personId, int count, BigDecimal lastDueTotal, LocalDate lastDate) {}

    /** A statement just taken, with the person's claims as they now stand. */
    public record Taken(Summary summary, List<Claim> claims) {}

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final SheetRepository sheets;
    private final RollupService rollup;
    private final SettingService settings;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateService rates, SheetRepository sheets, RollupService rollup,
            SettingService settings, Audit audit) {
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

    /** Each person's statements on the sheet, for those with any. */
    @Transactional(readOnly = true)
    public List<Summary> summaries(String sheetId) {
        Map<Long, List<Statement>> byPerson = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            byPerson.computeIfAbsent(s.getPersonId(), id -> new ArrayList<>()).add(s);
        }
        return byPerson.entrySet().stream().map(e -> summaryOf(e.getKey(), e.getValue())).toList();
    }

    /**
     * Take a statement for the person now, or as of a date (null: now) on or before the sheet's today and
     * not before they were added. As of a date, it binds their claims that counted at the end of it to
     * their figures as they stood then: every change made while the sheet's today was on or before the
     * date counts, no later one, and the date stands in for the sheet's today in every figure.
     */
    @Transactional
    public Taken take(String sheetId, String user, Long personId, LocalDate asOf) {
        // Lock the sheet so the claims and rates read are not changed while the statement is taken.
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        LocalDate today = sheet.getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (asOf != null && (asOf.isAfter(today) || !person.existedAt(asOf))) throw new StatementDateException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Bound bound = asOf == null ? bindNow(sheet, person, theirs) : bindAsOf(sheet, person, theirs, asOf);
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, bound.dueTotal(), asOf != null ? asOf : today, today,
                user.strip()));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user.strip() + " name=" + person.getName()
                + " due-total=" + (bound.dueTotal() == null ? "n/a" : bound.dueTotal().toPlainString())
                + " claims=" + bound.counted() + (asOf != null ? " asof=" + asOf : ""));
        rollup.recordChanges(sheetId, user, before);
        return new Taken(summaryOf(personId, statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId)),
                theirs);
    }

    /** What a statement bound: the due-total (null when n/a) and how many claims counted toward it. */
    private record Bound(BigDecimal dueTotal, int counted) {}

    /** Bind the person's claims that count now to their figures now, the rest unbound. */
    private Bound bindNow(Sheet sheet, Person person, List<Claim> theirs) {
        String sheetId = sheet.getId();
        LocalDate today = sheet.getToday();
        RateLevels levels = rates.levels(sheetId);
        boolean fixed = false;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!person.counts(claim, today)) {
                claim.unstate(today);
                continue;
            }
            BigDecimal figure = claimService.figureOf(sheetId, person, claim);
            BigDecimal due = claimService.chargedFigureOf(sheetId, person, claim);
            if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) fixed = true;
            // a due under a grade they had on its date but not today counts like a fixed one
            if (due != null && !person.gradeOn(claim.getDate()).equals(person.gradeOn(today))) fixed = true;
            // and so does a due under a rate that is not the one in force today
            String grade = person.gradeOn(claim.getDate());
            if (due != null && !levels.inForce(grade, claim.getDate()).map(Rate::getId)
                    .equals(levels.inForce(grade, today).map(Rate::getId))) fixed = true;
            claim.state(figure, today);
            if (due != null) total = total.add(due);
            counted++;
        }
        // As on screen: n/a when their grade has no rate today and none of the counted claims has a fixed
        // due, nor a due under another grade they had on its date or under a rate not in force today.
        return new Bound(sheet.capped(
                fixed || levels.inForce(person.gradeOn(today), today).isPresent() ? total : null), counted);
    }

    /**
     * Bind the person's claims that counted at the end of the date to their figures as they stood then, the
     * rest unbound: the status, settled due and binding each had then, their holds and retirement, grades,
     * rates and dated settings as recorded by then, with the date in place of the sheet's today.
     */
    private Bound bindAsOf(Sheet sheet, Person person, List<Claim> theirs, LocalDate asOf) {
        LocalDate today = sheet.getToday();
        RateLevels ratesThen = rates.levelsAsOf(sheet.getId(), asOf);
        Map<String, LocalDate> datesByNumber = Rounding.datesByNumber(theirs);
        boolean fixed = false;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            ClaimStatus status = claim.statusAt(asOf);
            if (status == null || !status.counts() || claim.isDeferredAt(status, asOf)) {
                claim.unstate(today);
                continue;
            }
            // a PENDING claim showed its live due, unbound
            ClaimBinding binding = status == ClaimStatus.PENDING ? null : claim.bindingAt(asOf);
            if (status.isOpen() && binding == null
                    && (person.heldOn(claim.getDate(), asOf) || person.retiredAt(asOf))) {
                claim.unstate(today);
                continue;
            }
            String grade = person.gradeOn(claim.getDate(), asOf);
            RoundingMode rounding = Rounding.of(Rounding.basisOf(claim, datesByNumber), sheet.getPolicyDate(), asOf);
            BigDecimal settled = status == ClaimStatus.SETTLED ? claim.settledDueAt(asOf) : null;
            BigDecimal figure = settled != null ? settled
                    : binding != null ? binding.getDue()
                    : ratesThen.inForce(grade, claim.getDate())
                            .map(rate -> claim.getAmount().multiply(rate.getPct()).movePointLeft(2).setScale(2, rounding))
                            .orElse(null);
            BigDecimal due = figure;
            if (settled == null) {
                if (status.isOpen()) {
                    due = LateCharge.withChargeAsOpen(claim, due, asOf, sheet.graceOn(grade, claim.getDate(), asOf),
                            d -> sheet.latePctOn(d, asOf), rounding);
                }
                if (due != null && !claim.isCorrection()) due = due.max(sheet.floorOn(claim.getDate(), asOf));
            }
            if (settled != null || (binding != null && binding.getDue() != null)) fixed = true;
            // a due under a grade they had on its date but not on the date counts like a fixed one
            if (due != null && !grade.equals(person.gradeOn(asOf, asOf))) fixed = true;
            // and so does a due under a rate that is not the one in force on the date
            if (due != null && !ratesThen.inForce(grade, claim.getDate()).equals(ratesThen.inForce(grade, asOf))) {
                fixed = true;
            }
            claim.state(figure, today);
            if (due != null) total = total.add(due);
            counted++;
        }
        boolean rated = ratesThen.inForce(person.gradeOn(asOf, asOf), asOf).isPresent();
        return new Bound(sheet.cappedAsOf(fixed || rated ? total : null, asOf), counted);
    }

    /** A steward cancels the person's last statement, unbinding the claims it bound. */
    @Transactional
    public Taken cancelLast(String sheetId, String user, Long personId) {
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new StatementCancelRefusedException();
        List<Statement> theirStatements = new ArrayList<>(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
        if (theirStatements.isEmpty()) throw new StatementCancelRefusedException();
        Statement last = theirStatements.remove(theirStatements.size() - 1);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        for (Claim claim : theirs) {
            if (claim.isStated()) claim.unstate(today);
        }
        claims.saveAllAndFlush(theirs);
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user.strip() + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(sheetId, user, before);
        return new Taken(summaryOf(personId, theirStatements), theirs);
    }

    private static Summary summaryOf(Long personId, List<Statement> list) {
        if (list.isEmpty()) return new Summary(personId, 0, null, null);
        Statement last = list.get(list.size() - 1);
        return new Summary(personId, list.size(), last.getDueTotal(), last.getDate());
    }
}
