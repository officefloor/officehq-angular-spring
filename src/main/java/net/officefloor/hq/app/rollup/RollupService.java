package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonSnapshot;
import net.officefloor.hq.app.person.PersonSnapshotRepository;
import net.officefloor.hq.app.pool.PoolCuts;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures a sheet shows: each person's claimed and due-total, and the sheet's totals. A change takes a
 * {@link #snapshot} before it and calls {@link #recordChanges} after it, so the audit gets one ROLLUP line per person
 * whose figures (claimed, due-total, overdue-total) moved (in list order) and a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /**
     * One person's figures; a null due-total is n/a. The overdue-total sums their overdue claims' dues. The
     * settled-total sums the figures of their counted settled claims that correct nothing, and the overdue count is how many of their counted
     * claims are overdue (whether or not they have a figure). The held-total sums the parts they hold of others'
     * shared claims (null when they hold none), which their due-total counts. The split settled-total and open-total
     * are the figures of their counted settled claims (corrections included) and of their counted open and pending
     * ones, before the cap, as their row shows them; the cut total is their cuts of the pools' pots.
     */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal,
            BigDecimal settledTotal, int overdueCount, BigDecimal heldTotal, BigDecimal splitSettledTotal,
            BigDecimal openTotal, BigDecimal cutTotal) {

        /**
         * The person's score: their settled-total divided by 100, minus 5 for each of their overdue claims, rounded
         * half to even to a whole number.
         */
        public BigDecimal score() {
            return settledTotal.subtract(BigDecimal.valueOf(500L * overdueCount))
                    .divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_EVEN);
        }
    }

    /** The figures of every person on a sheet, by person id in list order, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    /**
     * One person of a sheet as the tree counts them: their name and aliases, their due-total (null for n/a), their
     * overdue-total, settled-total, open-total and cut total.
     */
    public record Member(List<String> names, BigDecimal dueTotal, BigDecimal overdueTotal, BigDecimal settledTotal,
            BigDecimal openTotal, BigDecimal cutTotal) {
    }

    /**
     * Figures added up over sheets: their people (each person once, those whose names or aliases match being one),
     * the people's due-totals and overdue-totals trimmed once over all of them, per grade the due-totals of the people
     * on it today (retired people left out, n/a skipped), per status the count of claims in it, and the count of
     * overdue claims that count live; with every
     * sheet's people as members, for the totals to be worked out again with more of them.
     */
    public record Consolidated(List<Member> members, Map<String, BigDecimal> gradeDueTotals,
            Map<ClaimStatus, Integer> statusCounts, int overdueCount) {

        static Consolidated empty() {
            return new Consolidated(List.of(), new TreeMap<>(), new EnumMap<>(ClaimStatus.class), 0);
        }

        Consolidated plus(Consolidated o) {
            List<Member> all = new ArrayList<>(members);
            all.addAll(o.members);
            Map<String, BigDecimal> grades = new TreeMap<>(gradeDueTotals);
            o.gradeDueTotals.forEach((g, d) -> grades.merge(g, d, BigDecimal::add));
            Map<ClaimStatus, Integer> statuses = new EnumMap<>(ClaimStatus.class);
            statuses.putAll(statusCounts);
            o.statusCounts.forEach((s, n) -> statuses.merge(s, n, Integer::sum));
            return new Consolidated(all, grades, statuses, overdueCount + o.overdueCount);
        }

        /** The people over the tree, each once: those whose names or aliases match, ignoring case, are one. */
        public int peopleCount() {
            int[] group = new int[members.size()];
            Map<String, Integer> byName = new HashMap<>();
            for (int i = 0; i < members.size(); i++) {
                group[i] = i;
                for (String n : members.get(i).names()) {
                    Integer j = byName.putIfAbsent(n.toLowerCase(Locale.ROOT), i);
                    if (j != null) {
                        group[root(group, i)] = root(group, j);
                    }
                }
            }
            Set<Integer> roots = new HashSet<>();
            for (int i = 0; i < members.size(); i++) {
                roots.add(root(group, i));
            }
            return roots.size();
        }

        private static int root(int[] group, int i) {
            while (group[i] != i) {
                i = group[i] = group[group[i]];
            }
            return i;
        }

        /** The people's due-totals (n/a skipped), trimmed once over the tree. */
        public BigDecimal dueTotal() {
            return trimmedSum(members.stream().map(Member::dueTotal).filter(Objects::nonNull).toList());
        }

        /** The people's overdue-totals, trimmed once over the tree. */
        public BigDecimal overdueTotal() {
            return trimmedSum(members.stream().map(Member::overdueTotal).toList());
        }

        /** The people's settled-totals, trimmed once over the tree. */
        public BigDecimal settledTotal() {
            return trimmedSum(members.stream().map(Member::settledTotal).toList());
        }

        /** The people's open-totals, trimmed once over the tree. */
        public BigDecimal openTotal() {
            return trimmedSum(members.stream().map(Member::openTotal).toList());
        }

        /** The people's cut totals, trimmed once over the tree. */
        public BigDecimal cutTotal() {
            return trimmedSum(members.stream().map(Member::cutTotal).toList());
        }
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChain rates;
    private final SheetRepository sheets;
    private final Audit audit;
    private final PersonSnapshotRepository snapshotLines;
    private final AsOfService asOf;
    private final ObjectProvider<PoolCuts> poolCuts;

    public RollupService(PersonRepository people, ClaimRepository claims, RateChain rates, SheetRepository sheets,
            Audit audit, PersonSnapshotRepository snapshotLines, AsOfService asOf, ObjectProvider<PoolCuts> poolCuts) {
        this.asOf = asOf;
        this.poolCuts = poolCuts;
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
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        // the parts of shared claims: what each holder's part of a counted claim comes to, by holder, and what the
        // claim's own person gives away of it, by claim
        Map<Long, Person> byId = new HashMap<>();
        for (Person p : listed) {
            byId.put(p.getId(), p);
        }
        Map<Long, List<Part>> partsBy = new HashMap<>();
        Map<Long, BigDecimal> givenBy = new HashMap<>();
        for (Person p : listed) {
            for (Claim c : claimsBy.getOrDefault(p.getId(), List.of())) {
                if (c.getShares().isEmpty() || !p.countsLive(c, today)) {
                    continue;
                }
                BigDecimal figure = liveFigure(c, p, tiers, byTier, sheet);
                if (figure == null) {
                    continue;
                }
                for (ClaimShare share : c.getShares()) {
                    BigDecimal part = part(c, figure, share, byId.get(share.getPersonId()), tiers, byTier, sheet);
                    // a part at no rate is n/a for its holder and gives nothing away
                    if (part == null) {
                        continue;
                    }
                    partsBy.computeIfAbsent(share.getPersonId(), id -> new ArrayList<>()).add(new Part(c, part));
                    givenBy.merge(c.getId(), part, BigDecimal::add);
                }
            }
        }
        // each person's cut total, which the cap covers along with their due-total
        Map<Long, BigDecimal> cutTotals = sheet != null ? poolCuts.getObject().totals(sheetId, today) : Map.of();
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person p : listed) {
            // an open claim of a retired person counts in no live total, unless a statement binds it; nor does one
            // deferred on the sheet's today; a hold leaves nothing out
            List<Claim> counted = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsLive(c, today)).toList();
            // a claim correcting another does not count toward what is claimed
            BigDecimal claimed = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsClaimed(c, today) && c.getCorrects() == null)
                    .map(Claim::getAmount).reduce(ZERO, BigDecimal::add);
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            BigDecimal settled = ZERO;
            BigDecimal splitSettled = ZERO;
            BigDecimal open = ZERO;
            int overdueCount = 0;
            boolean kept = false;
            // the grades whose rate today decides n/a: the one in force today and each counted claim's
            Set<String> grades = new HashSet<>();
            grades.add(p.gradeOn(today));
            for (Claim c : counted) {
                grades.add(p.gradeOn(c.getDate()));
                kept |= c.getSettledDue() != null || (c.isBound() && c.getStatedDue() != null);
                BigDecimal figure = liveFigure(c, p, tiers, byTier, sheet);
                boolean late = isOverdue(c, p, sheet, today);
                if (late) {
                    overdueCount++;
                }
                if (figure != null) {
                    kept = true;
                    // a shared claim's due-total counts the figure less its holders' parts
                    BigDecimal own = figure.subtract(givenBy.getOrDefault(c.getId(), ZERO));
                    due = due.add(own);
                    if (late) {
                        overdue = overdue.add(figure);
                    }
                    // a correction's figure counts toward no score
                    if (c.getStatus() == ClaimStatus.SETTLED && c.getCorrects() == null) {
                        settled = settled.add(own);
                    }
                    if (c.getStatus() == ClaimStatus.SETTLED) {
                        splitSettled = splitSettled.add(own);
                    } else if (c.getStatus().isOpen()) {
                        open = open.add(own);
                    }
                }
            }
            // the parts the person holds of others' claims count as figures of theirs
            BigDecimal held = partsBy.containsKey(p.getId()) ? ZERO : null;
            for (Part part : partsBy.getOrDefault(p.getId(), List.of())) {
                kept = true;
                due = due.add(part.figure());
                held = held.add(part.figure());
                if (part.claim().getStatus() == ClaimStatus.SETTLED && part.claim().getCorrects() == null) {
                    settled = settled.add(part.figure());
                }
                if (part.claim().getStatus() == ClaimStatus.SETTLED) {
                    splitSettled = splitSettled.add(part.figure());
                } else if (part.claim().getStatus().isOpen()) {
                    open = open.add(part.figure());
                }
            }
            // with no rate in force today for any of those grades and no claim with a figure (kept, or live at a
            // rate that has since ended), the due-total is n/a
            if (!kept && (today == null || grades.stream()
                    .allMatch(g -> Rate.inForce(byGrade.getOrDefault(g, List.of()), today) == null))) {
                due = null;
            }
            // the due-total is at most the sheet's cap less the person's cut total
            if (sheet != null && sheet.getCap() != null) {
                due = sheet.capped(due, cutTotals.get(p.getId()));
            }
            BigDecimal cut = cutTotals.getOrDefault(p.getId(), ZERO).setScale(2);
            figures.put(p.getId(), new Figures(p.getName(), claimed, due, overdue, settled, overdueCount, held,
                    splitSettled, open, cut));
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
        Consolidated sum = Consolidated.empty();
        for (String id : descendantIds(sheetId)) {
            sum = sum.plus(sheetFigures(id));
        }
        return sum;
    }

    /** Whether the store holds the sheet. */
    @Transactional(readOnly = true)
    public boolean sheetExists(String sheetId) {
        return sheets.findById(sheetId).isPresent();
    }

    /**
     * Per grade, the due-totals of the people on it today on the sheet as the store holds it now (retired people left
     * out, n/a skipped); empty for a sheet that does not exist.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> gradeDueTotals(String sheetId) {
        if (sheets.findById(sheetId).isEmpty()) {
            return new TreeMap<>();
        }
        return sheetFigures(sheetId).gradeDueTotals();
    }

    /** One sheet's figures as {@link Consolidated} adds them up, as the store holds them (or as of a date, inside one). */
    private Consolidated sheetFigures(String sheetId) {
        Snapshot s = snapshot(sheetId);
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        Map<String, BigDecimal> grades = new TreeMap<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Figures f = s.people().get(p.getId());
            String grade = p.gradeOn(today);
            if (p.getRetiredFrom() != null || f == null || grade == null) {
                continue;
            }
            grades.merge(grade, f.dueTotal() == null ? ZERO : f.dueTotal(), BigDecimal::add);
        }
        Map<ClaimStatus, Integer> statuses = new EnumMap<>(ClaimStatus.class);
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            statuses.merge(c.getStatus(), 1, Integer::sum);
        }
        List<Member> members = new ArrayList<>();
        int overdueCount = 0;
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Figures f = s.people().get(p.getId());
            if (f != null) {
                overdueCount += f.overdueCount();
                List<String> names = new ArrayList<>(List.of(p.getName()));
                names.addAll(p.getAliases());
                members.add(new Member(names, f.dueTotal(), f.overdueTotal(), f.splitSettledTotal(), f.openTotal(),
                        f.cutTotal()));
            }
        }
        return new Consolidated(members, grades, statuses, overdueCount);
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

    /** One person of a sheet of the tree as the tree export lists them: the sheet, name, grade today and due-total. */
    public record TreePerson(String sheet, String name, String grade, BigDecimal dueTotal) {
    }

    /**
     * The people of the other sheets of the sheet's whole tree (its topmost ancestor and every descendant of that, the
     * sheet itself left out), sheets by id and each sheet's people in list order, as the store holds them now.
     */
    @Transactional(readOnly = true)
    public List<TreePerson> treePeople(String sheetId) {
        String root = sheetId;
        Set<String> seen = new HashSet<>(Set.of(root));
        for (String up = parentOf(root); up != null && seen.add(up); up = parentOf(up)) {
            root = up;
        }
        List<String> tree = new ArrayList<>(List.of(root));
        tree.addAll(descendantIds(root));
        tree.remove(sheetId);
        Collections.sort(tree);
        List<TreePerson> rows = new ArrayList<>();
        for (String id : tree) {
            Snapshot s = snapshot(id);
            LocalDate today = sheets.findById(id).map(Sheet::getToday).orElse(null);
            for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                Figures f = s.people().get(p.getId());
                rows.add(new TreePerson(id, p.getName(), p.gradeOn(today), f == null ? null : f.dueTotal()));
            }
        }
        return rows;
    }

    private String parentOf(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getParentId).orElse(null);
    }

    /**
     * The consolidated figures as of the day: the sheet and each of its descendants (as linked now), each as it stood at
     * the end of the day with the day as its today (see {@link AsOfService#storeAsOf}), added up.
     */
    public Consolidated consolidatedAsOf(String sheetId, LocalDate day) {
        List<String> tree = new ArrayList<>(List.of(sheetId));
        tree.addAll(descendantIds(sheetId));
        Consolidated sum = Consolidated.empty();
        for (String id : tree) {
            sum = sum.plus(asOf.readAsOf(id, day, () -> sheetFigures(id)));
        }
        return sum;
    }

    /** The sheet's own figures and its descendants' added up: the consolidated figures as the store holds them now. */
    @Transactional(readOnly = true)
    public Consolidated consolidated(String sheetId) {
        return sheetFigures(sheetId).plus(descendants(sheetId));
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

    /** Records, once the change commits, a SCORE line per person (in list order) with their score in the given snapshot. */
    public void recordScores(String by, String sheetId, Snapshot snapshot) {
        for (Figures f : snapshot.people().values()) {
            audit.recordOnCommit("SCORE sheet=" + sheetId + " by=" + by + " name=" + f.name() + " score="
                    + f.score().toPlainString());
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

    /** A holder's part of a shared claim: the claim, and what the holder's part of it comes to. */
    private record Part(Claim claim, BigDecimal figure) {
    }

    /**
     * A holder's part of a counted shared claim with the figure. A settled or bound claim keeps its parts as frozen: its
     * figure times the share divided by 100, rounded half to even. Otherwise the claim's amount times the share divided
     * by 100, times the rate the holder's own grade in force on the claim's date has (in the tier of its added mark)
     * divided by 100, rounded like the claim; null (n/a) when that grade has no rate.
     */
    private static BigDecimal part(Claim c, BigDecimal figure, ClaimShare share, Person holder,
            List<RateChain.Tier> tiers, Map<RateChain.Tier, Map<String, List<Rate>>> byTier, Sheet sheet) {
        if (c.getSettledDue() != null || c.isBound()) {
            return figure.multiply(share.getShare()).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
        }
        Map<String, List<Rate>> claimRates = byTier.get(tiers.stream()
                .filter(t -> t.upTo() == null || c.getAddedMark() <= t.upTo()).findFirst().orElseThrow());
        String grade = holder == null ? null : holder.gradeOn(c.getDate());
        Rate rate = grade == null ? null : Rate.inForce(claimRates.getOrDefault(grade, List.of()), c.getDate());
        if (rate == null) {
            return null;
        }
        return c.getAmount().multiply(share.getShare()).multiply(rate.getPct()).movePointLeft(4)
                .setScale(2, sheet.roundingOn(c.getRoundsOn()));
    }

    /**
     * The figure a counted claim of the person totals: a settled or bound claim's due as fixed or as shown when stated,
     * else its live due at the rate its person's grade in force on its date has (in the tier of its added mark).
     */
    private static BigDecimal liveFigure(Claim c, Person p, List<RateChain.Tier> tiers,
            Map<RateChain.Tier, Map<String, List<Rate>>> byTier, Sheet sheet) {
        Map<String, List<Rate>> claimRates = byTier.get(tiers.stream()
                .filter(t -> t.upTo() == null || c.getAddedMark() <= t.upTo()).findFirst().orElseThrow());
        BigDecimal figure = figure(c, claimRates.getOrDefault(p.gradeOn(c.getDate()), List.of()), sheet);
        if (c.getSettledDue() == null && !c.isBound()) {
            figure = ClaimService.liveDue(figure, c, p, sheet, c.getStatus().isOpen());
        }
        return figure;
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
