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
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.AsOfContext;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateLog;
import net.officefloor.hq.app.rate.RateLogRepository;
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
    private final RateLogRepository rateLogs;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimLogRepository logs,
            RateRepository rates, RateLogRepository rateLogs, SheetRepository sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.logs = logs;
        this.rates = rates;
        this.rateLogs = rateLogs;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now; empty when the sheet does not exist. */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        List<List<Rate>> sheetRates = rateChain(sheetId);
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

    /** Figures added up over sheets: the number of people, the due-totals and the overdue-totals (two decimals). */
    public record Consolidated(int people, String dueTotal, String overdueTotal) {
    }

    /**
     * The figures of the sheet's descendants (its children, their children and so on; the sheet itself left out), each
     * sheet's people, due-total and overdue-total as that sheet shows them, added up.
     */
    public Consolidated descendantFigures(String sheetId) {
        int count = 0;
        BigDecimal due = zero();
        BigDecimal overdue = zero();
        for (String id : descendants(sheetId)) {
            Snapshot figures = snapshot(id);
            count += figures.people().size();
            due = due.add(new BigDecimal(figures.dueTotal()));
            overdue = overdue.add(new BigDecimal(figures.overdueTotal()));
        }
        return new Consolidated(count, shown(due), shown(overdue));
    }

    /**
     * The figures of the sheet and its descendants as the store holds them now, each sheet's people, due-total and
     * overdue-total as that sheet shows them, added up.
     */
    public Consolidated consolidatedFigures(String sheetId) {
        Snapshot own = snapshot(sheetId);
        Consolidated descendants = descendantFigures(sheetId);
        return new Consolidated(own.people().size() + descendants.people(),
                shown(new BigDecimal(own.dueTotal()).add(new BigDecimal(descendants.dueTotal()))),
                shown(new BigDecimal(own.overdueTotal()).add(new BigDecimal(descendants.overdueTotal()))));
    }

    /** The ids of the sheet's descendants, each once, nearest first; a parent loop never brings back the sheet. */
    private List<String> descendants(String sheetId) {
        List<String> seen = new ArrayList<>(List.of(sheetId));
        for (int i = 0; i < seen.size(); i++) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(seen.get(i))) {
                if (!seen.contains(child.getId())) {
                    seen.add(child.getId());
                }
            }
        }
        return seen.subList(1, seen.size());
    }

    /** How many claims a candidate rate would change the due of, and the sum of new due minus current due (two decimals). */
    public record RatePreview(int count, String diff) {
    }

    /**
     * What the screen would show if the grade had the percent on every date, changing nothing: of the OPEN and PENDING
     * claims whose person had the grade on the claim's date, how many would show a different due, and the sum of each
     * new due minus its current one (an n/a due counting as 0.00). A claim bound by a statement keeps its stated due.
     */
    public RatePreview preview(String sheetId, String grade, BigDecimal pct) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        List<List<Rate>> sheetRates = rateChain(sheetId);
        List<Rate> own = new ArrayList<>(sheetRates.get(0).stream().filter(r -> !r.getGrade().equals(grade)).toList());
        own.add(new Rate(sheetId, grade, null, pct));
        List<List<Rate>> candidate = new ArrayList<>(sheetRates);
        candidate.set(0, own);
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            byId.put(person.getId(), person);
        }
        int count = 0;
        BigDecimal diff = zero();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.getStatus().isOpen() || !grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal now = dueOf(sheet, sheetRates, person, claim).map(RollupService::cents).orElse(null);
            BigDecimal then = dueOf(sheet, candidate, person, claim).map(RollupService::cents).orElse(null);
            if (Objects.equals(now, then)) {
                continue;
            }
            count++;
            diff = diff.add(then == null ? zero() : then).subtract(now == null ? zero() : now);
        }
        return new RatePreview(count, shown(diff));
    }

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
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
        List<List<Rate>> sheetRates = rateChain(sheetId);
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
    private static Optional<BigDecimal> dueOf(Sheet sheet, List<List<Rate>> sheetRates, Person person, Claim claim) {
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

    /** How many levels of parent sheets a grade with no rate in force on a sheet falls back on. */
    public static final int PARENT_LEVELS = 3;

    /**
     * The sheet's rates, then its parent's, its parent's parent's and so on up to {@link #PARENT_LEVELS} levels up, in
     * grade order then by from-date: the rates a grade's rate on a date is looked for in, nearest first. While the
     * request shows the sheet as of a date, the parents' rates are as they stood then.
     */
    public List<List<Rate>> rateChain(String sheetId) {
        return rateChain(sheetId, AsOfContext.current());
    }

    /**
     * As {@link #rateChain(String)}, with the parents' rates as they stood at the end of {@code asOf} (null: as they
     * are now).
     */
    public List<List<Rate>> rateChain(String sheetId, LocalDate asOf) {
        List<List<Rate>> chain = new ArrayList<>();
        chain.add(rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId));
        List<String> seen = new ArrayList<>(List.of(sheetId));
        String parent = sheets.findById(sheetId).map(Sheet::getParent).orElse(null);
        while (parent != null && chain.size() <= PARENT_LEVELS && !seen.contains(parent)) {
            seen.add(parent);
            chain.add(asOf == null ? rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(parent) : ratesAsOf(parent, asOf));
            parent = sheets.findById(parent).map(Sheet::getParent).orElse(null);
        }
        return chain;
    }

    /** The sheet's rates as they stood at the end of {@code asOf}, by its rate log, in grade order then by from-date. */
    private List<Rate> ratesAsOf(String sheetId, LocalDate asOf) {
        Map<String, List<Rate>> now = new TreeMap<>();
        for (Rate rate : rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId)) {
            now.computeIfAbsent(rate.getGrade(), g -> new ArrayList<>()).add(rate);
        }
        Map<String, List<RateLog>> set = new TreeMap<>();
        for (RateLog log : rateLogs.findBySheetIdOrderByIdAsc(sheetId)) {
            set.computeIfAbsent(log.getGrade(), g -> new ArrayList<>()).add(log);
            now.computeIfAbsent(log.getGrade(), g -> new ArrayList<>());
        }
        List<Rate> stood = new ArrayList<>();
        for (Map.Entry<String, List<Rate>> grade : now.entrySet()) {
            stood.addAll(RateLog.stood(sheetId, grade.getKey(), grade.getValue(),
                    set.getOrDefault(grade.getKey(), List.of()), asOf).stream()
                    .sorted(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList());
        }
        return stood;
    }

    /**
     * The grade's rate in force on the date: the sheet's own, else the first in force of its parents' (nearest
     * first); empty when none is.
     */
    public static Optional<Rate> rateOn(List<List<Rate>> chain, String grade, LocalDate date) {
        for (List<Rate> level : chain) {
            Optional<Rate> found = rateOnLevel(level, grade, date);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Rate> rateOnLevel(List<Rate> sheetRates, String grade, LocalDate date) {
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
