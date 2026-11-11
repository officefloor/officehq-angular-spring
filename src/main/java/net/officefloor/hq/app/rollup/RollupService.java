package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * Works out each person's claimed and due-total and the sheet's totals as the screen shows them, so a change can
 * record in the audit whose figures it moved. Take a {@link #snapshot(String)} before the change, then call
 * {@link #recordChanges(String, String, Snapshot)} after its own audit records.
 */
@Service
public class RollupService {

    /** A person's figures as shown: claimed with two decimals, due-total with two decimals or n/a. */
    public record Figures(String name, String claimed, String dueTotal) {
    }

    /** Everyone's figures, by person id in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateRepository rates,
            SheetRepository sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now; empty when the sheet does not exist. */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
        Map<Long, List<Claim>> counted = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus().counts()) {
                counted.computeIfAbsent(claim.getPersonId(), id -> new ArrayList<>()).add(claim);
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        BigDecimal dueTotal = zero();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = counted.getOrDefault(person.getId(), List.of()).stream()
                    .filter(claim -> person.counts(claim.getStatus()))
                    .toList();
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            boolean anyDue = today != null && rateOn(sheetRates, person.gradeOn(today), today).isPresent();
            for (Claim claim : theirs) {
                claimed = claimed.add(claim.getAmount());
                if (claim.getSettledDue() != null || claim.getStatedDue() != null) {
                    anyDue = true;
                }
                Optional<BigDecimal> claimDue = dueOf(sheet, sheetRates, person, claim);
                if (claimDue.isPresent()) {
                    due = due.add(claimDue.get());
                    // A due under a grade the person had on an earlier date counts too.
                    if (today != null && !claim.getDate().isAfter(today)) {
                        anyDue = true;
                    }
                }
            }
            claimedTotal = claimedTotal.add(claimed);
            if (anyDue) {
                dueTotal = dueTotal.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), shown(claimed), anyDue ? shown(due) : "n/a"));
        }
        return new Snapshot(figures, shown(claimedTotal), shown(dueTotal));
    }

    /**
     * The due-total of the dates from and to, both inclusive, as the screen shows a period's: the dues of the OPEN
     * and SETTLED claims dated in them, skipping those with no due and the OPEN ones of a person on hold.
     */
    public BigDecimal dueTotalBetween(String sheetId, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            byId.put(person.getId(), person);
        }
        BigDecimal total = zero();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !person.counts(claim.getStatus()) || claim.getDate().isBefore(from)
                    || claim.getDate().isAfter(to)) {
                continue;
            }
            Optional<BigDecimal> due = dueOf(sheet, sheetRates, person, claim);
            if (due.isPresent()) {
                total = total.add(due.get());
            }
        }
        return total.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Record, once the transaction commits, a ROLLUP line for each person whose figures moved since the snapshot (a
     * person new since then counting as moved), then a TOTALS line when the sheet's totals moved.
     */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures was = before.people().get(entry.getKey());
            Figures now = entry.getValue();
            if (was != null && was.claimed().equals(now.claimed()) && was.dueTotal().equals(now.dueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                    + " claimed=" + now.claimed() + " due-total=" + now.dueTotal());
        }
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal());
        }
    }

    /**
     * As on the screen: the settled due, else the figure (the stated due, else the amount at the rate on the claim's
     * date raised to the sheet's floor) with the late charge of an overdue claim added on top.
     */
    private static Optional<BigDecimal> dueOf(Sheet sheet, List<Rate> sheetRates, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        Optional<BigDecimal> figure = claim.isStated() ? Optional.ofNullable(claim.getStatedDue())
                : rateOn(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                        .map(Rate::getPct)
                        .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2)
                                .setScale(2, RoundingMode.HALF_EVEN))
                        .map(due -> sheet == null ? due : sheet.floored(due));
        return sheet == null ? figure
                : figure.map(f -> sheet.withLateCharge(f, claim.getStatus() == ClaimStatus.OPEN, claim.getDate()));
    }

    private static Optional<Rate> rateOn(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String shown(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
