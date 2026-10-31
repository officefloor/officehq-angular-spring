package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The figures of a sheet as the screen shows them: each person's claimed, due-total and overdue-total, and the
 * sheet's claimed-total, due-total and overdue-total (the latter two trimmed of the single highest and lowest
 * person's figure when three or more people have one). A change takes the figures before it and, once made, records
 * a ROLLUP line for each person whose figures moved and a TOTALS line when any of the sheet's totals moved.
 */
@Service
public class Rollup {

    /** A person's figures: claimed, due-total and overdue-total, each with two decimals; due-total is "n/a" when they
     *  have none. */
    public record PersonFigures(String name, String claimed, String dueTotal, String overdueTotal) {
    }

    /** A sheet's figures: each person's, by id in the order they were added, and the sheet's totals. */
    public record Figures(Map<Long, PersonFigures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    private static final String NOT_AVAILABLE = "n/a";

    private final SheetRepository sheets;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChains rates;
    private final Audit audit;

    public Rollup(SheetRepository sheets, PersonRepository people, ClaimRepository claims, RateChains rates,
            Audit audit) {
        this.sheets = sheets;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now. */
    public Figures of(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        RateChain sheetRates = rates.of(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Long, PersonFigures> byPerson = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            List<Claim> counting = sheetClaims.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && person.counts(c, today)
                            && !c.isDeferredOn(today))
                    .toList();
            BigDecimal claimed = counting.stream().map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            claimedTotal = claimedTotal.add(claimed);
            // As on screen: with no rate in force today for their grade on the sheet's today, nor for the grade
            // any of their claims is at, and no claim that has a due, the person has no due-total.
            List<BigDecimal> dues = counting.stream()
                    .map(c -> dueOf(c, person.gradeOn(c.getDate()), sheetRates, sheet, sheetClaims))
                    .filter(Objects::nonNull)
                    .toList();
            boolean anyRate = today != null && Stream.concat(Stream.of(person.gradeOn(today)),
                            counting.stream().map(c -> person.gradeOn(c.getDate())))
                    .anyMatch(grade -> sheetRates.inForceOn(grade, today).isPresent());
            String due = NOT_AVAILABLE;
            if (!dues.isEmpty() || anyRate) {
                BigDecimal sum = dues.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                BigDecimal total = sheet == null ? sum : sheet.capped(sum);
                dueFigures.add(total);
                due = total.toPlainString();
            }
            // As on screen: the dues of the claims that count which are open and due by (plus their grade's grace
            // days) a day before the sheet's today.
            BigDecimal overdue = counting.stream()
                    .filter(c -> today != null && ClaimService.isOverdue(c, sheet, person.gradeOn(c.getDate())))
                    .map(c -> dueOf(c, person.gradeOn(c.getDate()), sheetRates, sheet, sheetClaims))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            overdueFigures.add(overdue);
            byPerson.put(person.getId(),
                    new PersonFigures(person.getName(), claimed.toPlainString(), due, overdue.toPlainString()));
        }
        return new Figures(byPerson, claimedTotal.toPlainString(), trimmedSum(dueFigures).toPlainString(),
                trimmedSum(overdueFigures).toPlainString());
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        BigDecimal highest = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal lowest = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(highest).subtract(lowest);
    }

    /** Each person's due-total for the dates from {@code from} to {@code to} inclusive, by person id, as the screen
     *  shows a person's period figure: the dues as shown now of their claims that counted by the status they had at the end
     *  of {@code to} (a held person's open ones dated within a
     *  hold and not stated do not) due by in it, none counting as 0; every person on the sheet has one. */
    public Map<Long, BigDecimal> dueByPersonBetween(String sheetId, LocalDate from, LocalDate to) {
        RateChain sheetRates = rates.of(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, Person> personOf = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            personOf.put(p.getId(), p);
            totals.put(p.getId(), BigDecimal.ZERO.setScale(2));
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || !person.countsAtEnd(claim, to, today) || claim.isDeferredOn(today) || claim.getDueBy().isBefore(from) || claim.getDueBy().isAfter(to)) {
                continue;
            }
            BigDecimal due = dueOf(claim, person.gradeOn(claim.getDate()), sheetRates, sheet, sheetClaims);
            if (due != null) {
                totals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return totals;
    }

    /** What a candidate rate would change: how many claims would show a different due, and the sum of the
     *  differences (new due minus current due), with two decimals. */
    public record RatePreview(int count, String diff) {
    }

    /** How the OPEN and PENDING claims of people on a grade (in force on each claim's date) would change were a rate
     *  to apply to that grade on every date: a claim bound by a statement does not change and a settled one is not
     *  counted; a due of none counts as 0 in the sum. Nothing is stored. */
    public RatePreview preview(String sheetId, String grade, BigDecimal rate) {
        RateChain sheetRates = rates.of(sheetId);
        RateChain candidate = sheetRates.withOwn(Stream.concat(
                sheetRates.levels().get(0).stream().filter(r -> !r.getGrade().equals(grade)),
                Stream.of(new Rate(sheetId, grade, null, rate.setScale(2, RoundingMode.UNNECESSARY)))).toList());
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        Map<Long, Person> personOf = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> personOf.put(p.getId(), p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || claim.getSettledDue() != null
                    || (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING)
                    || !grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal now = dueOf(claim, grade, sheetRates, sheet, sheetClaims);
            BigDecimal then = dueOf(claim, grade, candidate, sheet, sheetClaims);
            if (!Objects.equals(now, then)) {
                count++;
                diff = diff.add(then == null ? BigDecimal.ZERO : then)
                        .subtract(now == null ? BigDecimal.ZERO : now);
            }
        }
        return new RatePreview(count, diff.setScale(2).toPlainString());
    }

    /**
     * Record, once the change commits, how the sheet's figures moved since {@code before}: a ROLLUP line
     * for each person whose figures changed (one who did not exist before counting as changed), then a
     * TOTALS line if the sheet's totals changed. Nothing is recorded when no figure moved.
     */
    public void recordChanges(String sheetId, String user, Figures before) {
        Figures after = of(sheetId);
        after.people().forEach((id, figures) -> {
            PersonFigures was = before.people().get(id);
            if (was == null || !was.claimed().equals(figures.claimed()) || !was.dueTotal().equals(figures.dueTotal())
                    || !was.overdueTotal().equals(figures.overdueTotal())) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal()
                        + " overdue-total=" + figures.overdueTotal());
            }
        });
        if (!before.claimedTotal().equals(after.claimedTotal()) || !before.dueTotal().equals(after.dueTotal())
                || !before.overdueTotal().equals(after.overdueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
    }

    /** A claim's due: its settled figure, else its stated one (none while PENDING), else its amount at the rate in force on its date of
     *  its person's grade in force on that date (the sheet's own, else its nearest ancestor's), rounded as the claim rounds (by the sheet's policy date); an overdue claim's late charge is added on top of the figure, and the
     *  sum is raised to the floor in force on its date unless it is a correction. */
    private static BigDecimal dueOf(Claim claim, String grade, RateChain sheetRates, Sheet sheet, List<Claim> sheetClaims) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        RoundingMode rounding = sheet == null ? RoundingMode.HALF_EVEN
                : sheet.roundingFor(ClaimService.roundingDate(claim, sheetClaims));
        BigDecimal figure = claim.isBound() ? claim.getBoundDue()
                : sheetRates.inForceOn(grade, claim.getDate())
                        .map(rate -> ClaimService.liveFigure(claim, rate.getRate(), rounding))
                        .orElse(null);
        return figure == null || sheet == null ? figure
                : ClaimService.floored(claim, figure.add(ClaimService.lateCharge(claim, figure, sheet, grade)), sheet);
    }
}
