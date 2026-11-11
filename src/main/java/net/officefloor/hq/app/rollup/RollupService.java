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
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
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
 * Works out each person's claimed, due-total and overdue-total and the sheet's totals as the screen shows them, so a change can
 * record in the audit whose figures it moved. Take a {@link #snapshot(String)} before the change, then call
 * {@link #recordChanges(String, String, Snapshot)} after its own audit records.
 */
@Service
public class RollupService {

    /**
     * A person's figures as shown: claimed with two decimals, due-total (held down to the cap) with two decimals or
     * n/a, and overdue-total (the dues of their overdue claims) with two decimals.
     */
    public record Figures(String name, String claimed, String dueTotal, String overdueTotal) {
    }

    /** Everyone's figures, by person id in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimLogRepository logs,
            RateRepository rates, SheetRepository sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.logs = logs;
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
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = counted.getOrDefault(person.getId(), List.of()).stream()
                    .filter(claim -> person.counts(claim, today))
                    .toList();
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean anyDue = today != null && rateOn(sheetRates, person.gradeOn(today), today).isPresent();
            for (Claim claim : theirs) {
                claimed = claimed.add(claim.getAmount());
                if (claim.getSettledDue() != null || claim.getBoundDue() != null) {
                    anyDue = true;
                }
                Optional<BigDecimal> claimDue = dueOf(sheet, sheetRates, person, claim);
                if (claimDue.isPresent()) {
                    due = due.add(claimDue.get());
                    if (isOverdue(sheet, person, claim, today)) {
                        overdue = overdue.add(claimDue.get());
                    }
                    // A due under a grade the person had on an earlier date counts too.
                    if (today != null && !claim.getDate().isAfter(today)) {
                        anyDue = true;
                    }
                }
            }
            claimedTotal = claimedTotal.add(claimed);
            overdues.add(overdue);
            if (anyDue && sheet != null) {
                due = sheet.capped(due);
            }
            if (anyDue) {
                dues.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), shown(claimed), anyDue ? shown(due) : "n/a",
                    shown(overdue)));
        }
        return new Snapshot(figures, shown(claimedTotal), shown(trimmedSum(dues)), shown(trimmedSum(overdues)));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        List<BigDecimal> kept = new ArrayList<>(figures);
        if (kept.size() >= 3) {
            kept.sort(null);
            kept = kept.subList(1, kept.size() - 1);
        }
        return kept.stream().reduce(zero(), BigDecimal::add);
    }

    /**
     * Each person's due-total of the dates from and to, both inclusive, by person id for every person on the sheet, as
     * the screen shows a person's for a period: the dues as shown now of their claims due by a date in them that were OPEN,
     * PENDING or SETTLED at the end of the to-date by their status log, skipping those with no due and the open ones a
     * hold of theirs leaves out; 0.00 with none.
     */
    public Map<Long, BigDecimal> dueTotalsBetween(String sheetId, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            byId.put(person.getId(), person);
            totals.put(person.getId(), zero());
        }
        Map<Long, List<ClaimLog>> logOf = new LinkedHashMap<>();
        for (ClaimLog line : logs.findBySheetIdOrderByIdAsc(sheetId)) {
            logOf.computeIfAbsent(line.getClaimId(), id -> new ArrayList<>()).add(line);
        }
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            ClaimStatus status = statusOn(claim, logOf.getOrDefault(claim.getId(), List.of()), to);
            if (person == null || !person.countsAsOf(claim, status, today) || claim.getDueBy().isBefore(from)
                    || claim.getDueBy().isAfter(to)) {
                continue;
            }
            dueOf(sheet, sheetRates, person, claim).ifPresent(due -> totals.merge(person.getId(), due, BigDecimal::add));
        }
        totals.replaceAll((id, total) -> total.setScale(2, RoundingMode.HALF_EVEN));
        return totals;
    }

    /**
     * Record, once the transaction commits, a ROLLUP line for each person whose figures moved since the snapshot (a
     * person new since then counting as moved), then a TOTALS line when any of the sheet's three totals moved.
     */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures was = before.people().get(entry.getKey());
            Figures now = entry.getValue();
            if (was != null && was.claimed().equals(now.claimed()) && was.dueTotal().equals(now.dueTotal())
                    && was.overdueTotal().equals(now.overdueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                    + " claimed=" + now.claimed() + " due-total=" + now.dueTotal()
                    + " overdue-total=" + now.overdueTotal());
        }
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
    }

    /**
     * As on the screen: the settled due, else the figure (the stated due, else the amount at the rate on the claim's
     * date, rounded as the sheet's policy date has the claim round) with the late charge of an overdue claim added on
     * top, raised last to the floor in force on the claim's date; a correction has neither floor nor late charge.
     */
    private static Optional<BigDecimal> dueOf(Sheet sheet, List<Rate> sheetRates, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        Optional<BigDecimal> figure = claim.isBound() ? Optional.ofNullable(claim.getStatedDue())
                : rateOn(sheetRates, person.gradeOn(claim.getDate()), claim.getDate())
                        .map(Rate::getPct)
                        .map(pct -> sheet == null
                                ? claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN)
                                : sheet.rateStep(claim.getAmount(), pct, claim.getRoundingDate()));
        return sheet == null || claim.isCorrection() ? figure
                : figure.map(f -> sheet.floored(sheet.withLateCharge(f, claim.getStatus().isOpen(),
                        sheet.overdueBy(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate()),
                        claim.getDate()), claim.getDate()));
    }

    /**
     * The status the claim had at the end of the date by its log (oldest first): its last line logged on or before the
     * date; null when it has lines but none by then. With no log at all, its status now.
     */
    private static ClaimStatus statusOn(Claim claim, List<ClaimLog> log, LocalDate date) {
        if (log.isEmpty()) {
            return claim.getStatus();
        }
        ClaimStatus found = null;
        for (ClaimLog line : log) {
            if (!line.getDate().isAfter(date)) {
                found = line.getStatus();
            }
        }
        return found;
    }

    /**
     * As on the screen: an OPEN or PENDING claim whose due-by, plus the grace days its person's grade has on its date,
     * is before the sheet's today.
     */
    private static boolean isOverdue(Sheet sheet, Person person, Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy() != null
                && sheet.overdueBy(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate()).isBefore(today);
    }

    private static Optional<Rate> rateOn(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.appliesOn(date));
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String shown(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
