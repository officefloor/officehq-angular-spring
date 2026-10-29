package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonSnapshot;
import net.officefloor.hq.app.person.PersonSnapshotRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures a sheet shows: each person's claimed and due-total, and the sheet's totals. A change takes a
 * {@link #snapshot} before it and calls {@link #recordChanges} after it, so the audit gets one ROLLUP line per person
 * whose figures (claimed, due-total, overdue-total) moved (in list order) and a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /** One person's figures; a null due-total is n/a. The overdue-total sums their overdue claims' dues. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The figures of every person on a sheet, by person id in list order, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    /** Figures added up over sheets: their people, and each sheet's due-total and overdue-total. */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChain rates;
    private final SheetRepository sheets;
    private final Audit audit;
    private final PersonSnapshotRepository snapshotLines;
    private final AsOfService asOf;

    public RollupService(PersonRepository people, ClaimRepository claims, RateChain rates, SheetRepository sheets,
            Audit audit, PersonSnapshotRepository snapshotLines, AsOfService asOf) {
        this.asOf = asOf;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.snapshotLines = snapshotLines;
    }

    /** The sheet's figures as the store holds them now (as the screen computes them). */
    public Snapshot snapshot(String sheetId) {
        claims.flush();
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        // the rates by grade for each tier of claims by the mark they were added at; the last is the rates as they
        // apply on the sheet, forward-only ones included, which decide n/a today
        List<RateChain.Tier> tiers = rates.tiers(sheetId);
        Map<RateChain.Tier, Map<String, List<Rate>>> byTier = new HashMap<>();
        for (RateChain.Tier t : tiers) {
            Map<String, List<Rate>> by = new HashMap<>();
            for (Rate r : t.rates()) {
                by.computeIfAbsent(r.getGrade(), g -> new ArrayList<>()).add(r);
            }
            byTier.put(t, by);
        }
        Map<String, List<Rate>> byGrade = byTier.get(tiers.get(tiers.size() - 1));
        Map<Long, List<Claim>> claimsBy = new HashMap<>();
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (c.getStatus().counts()) {
                claimsBy.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            // an open claim dated within one of the person's holds counts in no live total, unless a statement binds it;
            // nor does one deferred on the sheet's today
            List<Claim> counted = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsLive(c, today)).toList();
            // a held claim's amount still counts toward what is claimed; a claim correcting another does not
            BigDecimal claimed = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsClaimed(c, today) && c.getCorrects() == null)
                    .map(Claim::getAmount).reduce(ZERO, BigDecimal::add);
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            boolean kept = false;
            // the grades whose rate today decides n/a: the one in force today and each counted claim's
            Set<String> grades = new HashSet<>();
            grades.add(p.gradeOn(today));
            for (Claim c : counted) {
                grades.add(p.gradeOn(c.getDate()));
                kept |= c.getSettledDue() != null || (c.isBound() && c.getStatedDue() != null);
                // the claim's rate is the one its person's grade in force on its date has
                Map<String, List<Rate>> claimRates = byTier.get(tiers.stream()
                        .filter(t -> t.upTo() == null || c.getAddedMark() <= t.upTo()).findFirst().orElseThrow());
                BigDecimal figure = figure(c, claimRates.getOrDefault(p.gradeOn(c.getDate()), List.of()), sheet);
                // a settled or bound claim's figure is its due as fixed or as shown when stated
                if (c.getSettledDue() == null && !c.isBound()) {
                    figure = ClaimService.liveDue(figure, c, p, sheet, c.getStatus().isOpen());
                }
                if (figure != null) {
                    kept = true;
                    due = due.add(figure);
                    if (isOverdue(c, p, sheet, today)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            // with no rate in force today for any of those grades and no claim with a figure (kept, or live at a
            // rate that has since ended), the due-total is n/a
            if (!kept && (today == null || grades.stream()
                    .allMatch(g -> Rate.inForce(byGrade.getOrDefault(g, List.of()), today) == null))) {
                due = null;
            }
            // the due-total is at most the sheet's cap
            if (sheet != null) {
                due = sheet.capped(due);
            }
            figures.put(p.getId(), new Figures(p.getName(), claimed, due, overdue));
            claimedTotal = claimedTotal.add(claimed);
            overdues.add(overdue);
            if (due != null) {
                dues.add(due);
            }
        }
        return new Snapshot(figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /**
     * The sheet's descendants' figures added up: each sheet naming it as parent, their children, and so on, each
     * sheet counted once and the sheet itself left out (the screen adds its own figures as it shows them).
     */
    @Transactional(readOnly = true)
    public Consolidated descendants(String sheetId) {
        int peopleCount = 0;
        BigDecimal due = ZERO;
        BigDecimal overdue = ZERO;
        for (String id : descendantIds(sheetId)) {
            Snapshot s = snapshot(id);
            peopleCount += s.people().size();
            due = due.add(s.dueTotal());
            overdue = overdue.add(s.overdueTotal());
        }
        return new Consolidated(peopleCount, due, overdue);
    }

    /**
     * The ids of the sheet's descendants as linked now: each sheet naming it as parent, their children, and so on, each
     * once and the sheet itself left out.
     */
    private List<String> descendantIds(String sheetId) {
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        ArrayDeque<String> toVisit = new ArrayDeque<>(List.of(sheetId));
        List<String> ids = new ArrayList<>();
        while (!toVisit.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(toVisit.poll())) {
                if (seen.add(child.getId())) {
                    toVisit.add(child.getId());
                    ids.add(child.getId());
                }
            }
        }
        return ids;
    }

    /**
     * The consolidated figures as of the day: the sheet and each of its descendants (as linked now), each as it stood at
     * the end of the day with the day as its today (see {@link AsOfService#storeAsOf}), added up.
     */
    public Consolidated consolidatedAsOf(String sheetId, LocalDate day) {
        List<String> tree = new ArrayList<>(List.of(sheetId));
        tree.addAll(descendantIds(sheetId));
        int peopleCount = 0;
        BigDecimal due = ZERO;
        BigDecimal overdue = ZERO;
        for (String id : tree) {
            Snapshot s = asOf.readAsOf(id, day, () -> snapshot(id));
            peopleCount += s.people().size();
            due = due.add(s.dueTotal());
            overdue = overdue.add(s.overdueTotal());
        }
        return new Consolidated(peopleCount, due, overdue);
    }

    /** The sheet's own figures and its descendants' added up: the consolidated figures as the store holds them now. */
    @Transactional(readOnly = true)
    public Consolidated consolidated(String sheetId) {
        Snapshot own = snapshot(sheetId);
        Consolidated below = descendants(sheetId);
        return new Consolidated(own.people().size() + below.peopleCount(), own.dueTotal().add(below.dueTotal()),
                own.overdueTotal().add(below.overdueTotal()));
    }

    /** The sheet-total of the people's figures: with three or more, the single highest and lowest are left out. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(ZERO, BigDecimal::add);
        if (figures.size() >= 3) {
            sum = sum.subtract(Collections.max(figures)).subtract(Collections.min(figures));
        }
        return sum;
    }

    /**
     * Records, once the change commits, a SNAPSHOT line per person with their due-total in the given snapshot, and
     * keeps it as the person's snapshot line for the day being left.
     */
    public void recordSnapshot(String by, String sheetId, Snapshot snapshot, LocalDate day) {
        for (Map.Entry<Long, Figures> e : snapshot.people().entrySet()) {
            Figures f = e.getValue();
            if (day != null) {
                snapshotLines.save(new PersonSnapshot(e.getKey(), day, f.dueTotal()));
            }
            audit.recordOnCommit("SNAPSHOT sheet=" + sheetId + " by=" + by + " name=" + f.name() + " due-total="
                    + text(f.dueTotal()));
        }
    }

    /**
     * Records, once the change commits, a ROLLUP line for each person whose figures differ from the snapshot taken
     * before the change (a person new since then counts as changed), then a TOTALS line when the sheet's totals moved.
     */
    public void recordChanges(String by, String sheetId, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        String head = " sheet=" + sheetId + " by=" + by;
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures was = before.people().get(e.getKey());
            Figures now = e.getValue();
            if (was != null && was.claimed().equals(now.claimed()) && Objects.equals(was.dueTotal(), now.dueTotal())
                    && was.overdueTotal().equals(now.overdueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP" + head + " person=" + now.name() + " claimed=" + now.claimed().toPlainString()
                    + " due-total=" + text(now.dueTotal()) + " overdue-total=" + now.overdueTotal().toPlainString());
        }
        if (!before.claimedTotal().equals(after.claimedTotal()) || !before.dueTotal().equals(after.dueTotal())
                || !before.overdueTotal().equals(after.overdueTotal())) {
            audit.recordOnCommit("TOTALS" + head + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString() + " overdue-total="
                    + after.overdueTotal().toPlainString());
        }
        recordAlerts(head, sheetId, before, after);
    }

    /** Records, once the change commits, a ROLLUP line for every person on the sheet, then its TOTALS line. */
    public void recordAll(String by, String sheetId) {
        Snapshot now = snapshot(sheetId);
        String head = " sheet=" + sheetId + " by=" + by;
        for (Figures f : now.people().values()) {
            audit.recordOnCommit("ROLLUP" + head + " person=" + f.name() + " claimed=" + f.claimed().toPlainString()
                    + " due-total=" + text(f.dueTotal()) + " overdue-total=" + f.overdueTotal().toPlainString());
        }
        audit.recordOnCommit("TOTALS" + head + " claimed-total=" + now.claimedTotal().toPlainString() + " due-total="
                + now.dueTotal().toPlainString() + " overdue-total=" + now.overdueTotal().toPlainString());
        recordAlerts(head, sheetId, null, now);
    }

    /**
     * Records, once the change commits, an ALERT line (in list order) for each person whose due-total went from at or
     * below the sheet's alert threshold (or n/a, or not yet on the sheet) to above it; none while the threshold is off.
     */
    private void recordAlerts(String head, String sheetId, Snapshot before, Snapshot after) {
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) {
            return;
        }
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures was = before == null ? null : before.people().get(e.getKey());
            Figures now = e.getValue();
            if (above(now, threshold) && !above(was, threshold)) {
                audit.recordOnCommit("ALERT" + head + " name=" + now.name() + " due-total=" + text(now.dueTotal()));
            }
        }
    }

    private static boolean above(Figures f, BigDecimal threshold) {
        return f != null && f.dueTotal() != null && f.dueTotal().compareTo(threshold) > 0;
    }

    /**
     * The figure of a claim: its settled due, else the due a statement bound it to, else its rate figure (no floor),
     * rounded as the sheet's policy date decides for it.
     */
    private static BigDecimal figure(Claim c, List<Rate> rates, Sheet sheet) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        if (c.isBound()) {
            return c.getStatedDue();
        }
        Rate rate = Rate.inForce(rates, c.getDate());
        return rate == null ? null : ClaimService.dueOn(c.getAmount(), rate.getPct(), sheet.roundingOn(c.getRoundsOn()));
    }

    /**
     * Whether a claim is overdue: open (or pending), not deferred, and its due-by plus its grace (see
     * {@link ClaimService#lateFrom}) is before the sheet's today.
     */
    private static boolean isOverdue(Claim c, Person p, Sheet sheet, LocalDate today) {
        return c.getStatus().isOpen() && today != null && !c.isDeferredOn(today) && c.getDueBy() != null
                && ClaimService.lateFrom(c, p, sheet).isBefore(today);
    }

    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
