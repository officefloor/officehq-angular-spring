package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The figures of a sheet as the screen shows them: each person's claimed and due-total, and the
 * sheet's claimed-total and due-total. A change takes the figures before it and, once made, records
 * a ROLLUP line for each person whose figures moved and a TOTALS line when the sheet's totals moved.
 */
@Service
public class Rollup {

    /** A person's figures: claimed and due-total, each with two decimals; due-total is "n/a" when they have none. */
    public record PersonFigures(String name, String claimed, String dueTotal) {
    }

    /** A sheet's figures: each person's, by id in the order they were added, and the sheet's totals. */
    public record Figures(Map<Long, PersonFigures> people, String claimedTotal, String dueTotal) {
    }

    private static final String NOT_AVAILABLE = "n/a";

    private final SheetRepository sheets;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final Audit audit;

    public Rollup(SheetRepository sheets, PersonRepository people, ClaimRepository claims, RateRepository rates,
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
        List<Rate> sheetRates = rates.findBySheetId(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Long, PersonFigures> byPerson = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal dueTotal = BigDecimal.ZERO.setScale(2);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> counting = sheetClaims.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && person.counts(c))
                    .toList();
            BigDecimal claimed = counting.stream().map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            claimedTotal = claimedTotal.add(claimed);
            // As on screen: with no rate in force today for their grade on the sheet's today, nor for the grade
            // any of their claims is at, and no fixed figure, the person has no due-total.
            boolean anyFixed = counting.stream().anyMatch(c -> c.getSettledDue() != null || c.getBoundDue() != null);
            boolean anyRate = today != null && Stream.concat(Stream.of(person.gradeOn(today)),
                            counting.stream().map(c -> person.gradeOn(c.getDate())))
                    .anyMatch(grade -> Rate.inForceOn(ratesOf(sheetRates, grade), today).isPresent());
            String due = NOT_AVAILABLE;
            if (anyFixed || anyRate) {
                BigDecimal total = counting.stream().map(c -> dueOf(c, ratesOf(sheetRates, person.gradeOn(c.getDate())), sheet, sheetClaims))
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                dueTotal = dueTotal.add(total);
                due = total.toPlainString();
            }
            byPerson.put(person.getId(), new PersonFigures(person.getName(), claimed.toPlainString(), due));
        }
        return new Figures(byPerson, claimedTotal.toPlainString(), dueTotal.toPlainString());
    }

    /** The due-total of the claims that count (a held person's open ones dated within a hold and not stated do not) dated from {@code from} to {@code to} inclusive, as the
     *  screen shows a period's: each claim's due, none counting as 0. */
    public BigDecimal dueTotalBetween(String sheetId, LocalDate from, LocalDate to) {
        List<Rate> sheetRates = rates.findBySheetId(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        Map<Long, Person> personOf = new LinkedHashMap<>();
        people.findBySheetIdOrderByIdAsc(sheetId).forEach(p -> personOf.put(p.getId(), p));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || !person.counts(claim) || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)) {
                continue;
            }
            BigDecimal due = dueOf(claim, ratesOf(sheetRates, person.gradeOn(claim.getDate())), sheet, sheetClaims);
            if (due != null) {
                total = total.add(due);
            }
        }
        return total;
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
            if (was == null || !was.claimed().equals(figures.claimed()) || !was.dueTotal().equals(figures.dueTotal())) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal());
            }
        });
        if (!before.claimedTotal().equals(after.claimedTotal()) || !before.dueTotal().equals(after.dueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal());
        }
    }

    /** The rates of one grade among a sheet's. */
    private static List<Rate> ratesOf(List<Rate> sheetRates, String grade) {
        return sheetRates.stream().filter(r -> r.getGrade().equals(grade)).toList();
    }

    /** A claim's due: its settled figure, else its stated one (none while PENDING), else its amount at the rate in force on its date of
     *  its person's grade in force on that date, rounded as the claim rounds (by the sheet's policy date), raised to the sheet's floor unless it is a correction; an overdue claim's late charge is added on top of the figure. */
    private static BigDecimal dueOf(Claim claim, List<Rate> gradeRates, Sheet sheet, List<Claim> sheetClaims) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal floor = sheet == null ? null : sheet.getFloor();
        RoundingMode rounding = sheet == null ? RoundingMode.HALF_EVEN
                : sheet.roundingFor(ClaimService.roundingDate(claim, sheetClaims));
        BigDecimal figure = claim.getBoundDue() != null ? claim.getBoundDue()
                : Rate.inForceOn(gradeRates, claim.getDate())
                        .map(rate -> ClaimService.liveFigure(claim, rate.getRate(), floor, rounding))
                        .orElse(null);
        return figure == null || sheet == null ? figure : figure.add(ClaimService.lateCharge(claim, figure, sheet));
    }
}
