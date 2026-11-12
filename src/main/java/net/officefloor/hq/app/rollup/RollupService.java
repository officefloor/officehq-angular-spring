package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import jakarta.persistence.EntityManager;
import net.officefloor.hq.app.asof.AsOfContext;
import net.officefloor.hq.app.asof.SheetSnapshotService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonAlias;
import net.officefloor.hq.app.person.PersonAliasRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateLog;
import net.officefloor.hq.app.rate.RateLogRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rate.RateVersionRepository;
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
     * n/a, and overdue-total (the dues of their overdue claims) with two decimals; and the grade in force on the
     * sheet's today, or null when the sheet has none.
     */
    public record Figures(String name, String claimed, String dueTotal, String overdueTotal, String grade) {
    }

    /** Everyone's figures, by person id in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final RateRepository rates;
    private final RateLogRepository rateLogs;
    private final RateVersionRepository rateVersions;
    private final SheetRepository sheets;
    private final PersonAliasRepository aliases;
    private final Audit audit;
    private final SheetSnapshotService snapshots;
    private final EntityManager entities;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimLogRepository logs,
            RateRepository rates, RateLogRepository rateLogs, RateVersionRepository rateVersions, SheetRepository sheets,
            PersonAliasRepository aliases, Audit audit, SheetSnapshotService snapshots, EntityManager entities) {
        this.people = people;
        this.claims = claims;
        this.logs = logs;
        this.rates = rates;
        this.rateLogs = rateLogs;
        this.rateVersions = rateVersions;
        this.sheets = sheets;
        this.aliases = aliases;
        this.audit = audit;
        this.snapshots = snapshots;
        this.entities = entities;
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
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            List<Claim> theirs = counted.getOrDefault(person.getId(), List.of()).stream()
                    .filter(claim -> person.countsClaimed(claim, today))
                    .toList();
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean anyDue = today != null && rateOn(sheetRates, person.gradeOn(today), today).isPresent();
            for (Claim claim : theirs) {
                // A correction leaves claimed alone; only its due counts.
                if (claim.getCorrects() == null) {
                    claimed = claimed.add(claim.getAmount());
                }
                // A held claim stays in claimed; its due leaves the totals.
                if (!person.counts(claim, today)) {
                    continue;
                }
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
                    shown(overdue), today == null ? null : person.gradeOn(today)));
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
        for (String id : descendantsAsOf(sheetId)) {
            Snapshot figures = snapshot(id);
            count += figures.people().size();
            due = due.add(new BigDecimal(figures.dueTotal()));
            overdue = overdue.add(new BigDecimal(figures.overdueTotal()));
        }
        return new Consolidated(count, shown(due), shown(overdue));
    }

    /**
     * The figures of the sheet and its descendants, each sheet's people, due-total and overdue-total as that sheet
     * shows them, added up. While the request shows the sheet as of a date, every sheet of the tree is as it stood at
     * the end of that date, with the date as its today.
     */
    public Consolidated consolidatedFigures(String sheetId) {
        Snapshot own = snapshot(sheetId);
        Consolidated descendants = descendantFigures(sheetId);
        return new Consolidated(own.people().size() + descendants.people(),
                shown(new BigDecimal(own.dueTotal()).add(new BigDecimal(descendants.dueTotal()))),
                shown(new BigDecimal(own.overdueTotal()).add(new BigDecimal(descendants.overdueTotal()))));
    }

    /**
     * Every person on the sheet and the other sheets of its tree (its topmost ancestor and all that one's descendants)
     * whose name or an alias contains the text, case ignored, each as {@code <sheet id> <name> <due-total>} with the
     * due-total as that sheet shows it: the sheet's people first in list order, then the other sheets' by sheet id.
     */
    public List<String> search(String sheetId, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        List<String> others = new ArrayList<>();
        String root = root(sheetId);
        if (!root.equals(sheetId)) {
            others.add(root);
        }
        for (String id : descendants(root)) {
            if (!id.equals(sheetId)) {
                others.add(id);
            }
        }
        others.sort(null);
        List<String> order = new ArrayList<>(List.of(sheetId));
        order.addAll(others);
        List<String> lines = new ArrayList<>();
        for (String id : order) {
            Map<Long, List<String>> aliasesOf = new LinkedHashMap<>();
            for (PersonAlias alias : aliases.findBySheetId(id)) {
                aliasesOf.computeIfAbsent(alias.getPersonId(), p -> new ArrayList<>()).add(alias.getAlias());
            }
            Map<Long, Figures> figures = snapshot(id).people();
            for (Map.Entry<Long, Figures> entry : figures.entrySet()) {
                Figures person = entry.getValue();
                boolean matches = person.name().toLowerCase(Locale.ROOT).contains(wanted)
                        || aliasesOf.getOrDefault(entry.getKey(), List.of()).stream()
                                .anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(wanted));
                if (matches) {
                    lines.add(id + " " + named(person) + " " + person.dueTotal());
                }
            }
        }
        return lines;
    }

    /** The person's name as the screen shows it: with the grade in force on the sheet's today, when there is one. */
    private static String named(Figures person) {
        return person.grade() == null ? person.name() : person.name() + " (" + person.grade() + ")";
    }

    /**
     * The ids of the sheet's descendants, as {@link #descendants(String)}. While the request shows the sheet as of a
     * date, each is first put back as it stood at the end of that date, with the date as its today, for the request's
     * transaction alone (which the as-of view rolls back).
     */
    private List<String> descendantsAsOf(String sheetId) {
        List<String> ids = descendants(sheetId);
        LocalDate asOf = AsOfContext.current();
        if (asOf != null && !ids.isEmpty()) {
            entities.flush();
            for (String id : ids) {
                snapshots.restore(id, asOf);
            }
            // The sheets read to find them are stale now: read them afresh.
            entities.clear();
        }
        return ids;
    }

    /** The sheet's topmost ancestor (the sheet itself with no parent); a parent loop stops where it comes back. */
    private String root(String sheetId) {
        List<String> seen = new ArrayList<>(List.of(sheetId));
        String top = sheetId;
        String parent = sheets.findById(sheetId).map(Sheet::getParent).orElse(null);
        while (parent != null && !seen.contains(parent)) {
            seen.add(parent);
            top = parent;
            parent = sheets.findById(parent).map(Sheet::getParent).orElse(null);
        }
        return top;
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
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
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
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
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
     * Each person's carried figure for the period, by person id for every person on the sheet, as the screen shows it
     * while the period is open: the dues as shown now of their claims due by a date in one of the periods that ends
     * before it starts that were OPEN or PENDING at the end of its to-date by their status log and count then, skipping
     * those with no due, plus their opening balance for it; 0.00 with none.
     */
    public Map<Long, BigDecimal> carriedInto(String sheetId, List<Period> periods, Period period) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        List<List<Rate>> sheetRates = rateChain(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            byId.put(person.getId(), person);
            totals.put(person.getId(), zero());
        }
        Map<Long, List<ClaimLog>> logOf = new LinkedHashMap<>();
        for (ClaimLog line : logs.findBySheetIdOrderByIdAsc(sheetId)) {
            logOf.computeIfAbsent(line.getClaimId(), id -> new ArrayList<>()).add(line);
        }
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || claim.getDueBy() == null) {
                continue;
            }
            Optional<Period> from = periods.stream().filter(p -> p.contains(claim.getDueBy())).findFirst();
            if (from.isEmpty() || !from.get().getToDate().isBefore(period.getFromDate())) {
                continue;
            }
            ClaimStatus status = statusOn(claim, logOf.getOrDefault(claim.getId(), List.of()), period.getToDate());
            if (status == null || !status.isOpen() || !person.countsAsOf(claim, status, today)) {
                continue;
            }
            dueOf(sheet, sheetRates, person, claim).ifPresent(due -> totals.merge(person.getId(), due, BigDecimal::add));
        }
        period.getPersonOpenings().forEach((id, opening) -> totals.computeIfPresent(id, (k, total) -> total.add(opening)));
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
        recordAlerts(sheetId, user, before, after);
    }

    /**
     * Record, once the transaction commits, an ALERT line, in list order, for each person whose due-total went from at
     * or below the sheet's alert threshold (or none, or n/a) to above it; nothing while the threshold is off.
     */
    private void recordAlerts(String sheetId, String user, Snapshot before, Snapshot after) {
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) {
            return;
        }
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures was = before.people().get(entry.getKey());
            Figures now = entry.getValue();
            if (above(now, threshold) && !above(was, threshold)) {
                audit.recordOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + now.name()
                        + " due-total=" + now.dueTotal());
            }
        }
    }

    private static boolean above(Figures figures, BigDecimal threshold) {
        return figures != null && !"n/a".equals(figures.dueTotal())
                && new BigDecimal(figures.dueTotal()).compareTo(threshold) > 0;
    }

    /**
     * Record a ROLLUP line for every person on the sheet, in list order, then a TOTALS line, then an ALERT line for
     * each above the alert threshold.
     */
    public void recordAll(String sheetId, String user) {
        Snapshot now = snapshot(sheetId);
        for (Figures person : now.people().values()) {
            audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + person.name()
                    + " claimed=" + person.claimed() + " due-total=" + person.dueTotal()
                    + " overdue-total=" + person.overdueTotal());
        }
        audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + now.claimedTotal()
                + " due-total=" + now.dueTotal() + " overdue-total=" + now.overdueTotal());
        recordAlerts(sheetId, user, new Snapshot(Map.of(), null, null, null), now);
    }

    /**
     * As on the screen: the settled due, else the stated due (late charge included, as it was shown when bound), else
     * the amount at the rate on the claim's date, rounded as the sheet's policy date has the claim round, with the late
     * charge of an overdue claim added on top, raised last to the floor in force on the claim's date; a correction has
     * neither floor nor late charge.
     */
    private static Optional<BigDecimal> dueOf(Sheet sheet, List<List<Rate>> sheetRates, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        Optional<BigDecimal> figure = claim.isBound() ? Optional.ofNullable(claim.getStatedDue())
                : rateOn(sheetRates, person.gradeOn(claim.getDate()), claim.getDate(), claim.rateStamp())
                        .map(Rate::getPct)
                        .map(pct -> sheet == null
                                ? claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN)
                                : sheet.rateStep(claim.getAmount(), pct, claim.getDate(), claim.getRoundingDate()));
        return sheet == null || claim.isBound() || claim.isCorrection() ? figure
                : figure.map(f -> sheet.floored(sheet.withLateCharge(f, claim.getStatus().isOpen() && !claim.isWaived(),
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
     * grade order then by from-date; then, when the sheet was cloned, likewise those of the sheet it was cloned from and
     * its parents, then of that sheet's source and so on: the rates a grade's rate on a date is looked for in, nearest
     * first, each sheet once. While the request shows the sheet as of a date, the other sheets' rates are as they stood
     * then.
     */
    public List<List<Rate>> rateChain(String sheetId) {
        return rateChain(sheetId, AsOfContext.current());
    }

    /**
     * As {@link #rateChain(String)}, with the other sheets' rates as they stood at the end of {@code asOf} (null: as
     * they are now).
     */
    public List<List<Rate>> rateChain(String sheetId, LocalDate asOf) {
        List<List<Rate>> chain = new ArrayList<>();
        chain.add(withOlder(rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId)));
        List<String> seen = new ArrayList<>(List.of(sheetId));
        Sheet at = sheets.findById(sheetId).orElse(null);
        while (at != null) {
            String parent = at.getParent();
            for (int levels = 0; parent != null && levels < PARENT_LEVELS && !seen.contains(parent); levels++) {
                seen.add(parent);
                chain.add(ratesOf(parent, asOf));
                parent = sheets.findById(parent).map(Sheet::getParent).orElse(null);
            }
            String source = at.getSource();
            if (source == null || seen.contains(source)) {
                break;
            }
            seen.add(source);
            chain.add(ratesOf(source, asOf));
            at = sheets.findById(source).orElse(null);
        }
        return chain;
    }

    /** Another sheet's rates in the chain, as they stood at the end of {@code asOf} (null: as they are now). */
    private List<Rate> ratesOf(String sheetId, LocalDate asOf) {
        return asOf == null ? withOlder(rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId))
                : ratesAsOf(sheetId, asOf);
    }

    /**
     * The rates, each forward-only one with its older versions read with it, so a claim added before it was saved
     * falls back on the version it saw.
     */
    public List<Rate> withOlder(List<Rate> sheetRates) {
        for (Rate rate : sheetRates) {
            if (rate.getForwardAfter() != null) {
                rate.setOlder(rateVersions.findByRateIdOrderByVersionAsc(rate.getId()));
            }
        }
        return sheetRates;
    }

    /** The sheet's rates as they stood at the end of {@code asOf}, by its rate log, in grade order then by from-date. */
    private List<Rate> ratesAsOf(String sheetId, LocalDate asOf) {
        Map<String, List<Rate>> now = new TreeMap<>();
        for (Rate rate : withOlder(rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId))) {
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
     * The grade's rate in force on the date: the sheet's own, else the first in force further along its rate chain
     * (its parents', then its source's; nearest first); empty when none is.
     */
    public static Optional<Rate> rateOn(List<List<Rate>> chain, String grade, LocalDate date) {
        return rateOn(chain, grade, date, null);
    }

    /**
     * As {@link #rateOn(List, String, LocalDate)}, for the claim with the stamp: leaving out the forward-only rates
     * saved before it was added, each falling back on its newest older version the claim sees (null: without regard
     * to forward-only rates).
     */
    public static Optional<Rate> rateOn(List<List<Rate>> chain, String grade, LocalDate date, Long stamp) {
        for (List<Rate> level : chain) {
            Optional<Rate> found = rateOnLevel(level, grade, date, stamp);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Rate> rateOnLevel(List<Rate> sheetRates, String grade, LocalDate date, Long stamp) {
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade))
                .flatMap(r -> r.seenBy(stamp).stream())
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
