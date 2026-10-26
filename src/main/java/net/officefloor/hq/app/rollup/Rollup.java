package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolCuts;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rate.AppliedRates;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChangeRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.setting.DatedSettings;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.setting.SheetSettingDatedRepository;
import net.officefloor.hq.app.setting.SheetSettingRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total (late charges
 * included, held to the sheet's cap less their cut total, not below 0.00) and overdue-total (what is owed on their counting claims that are
 * open past their due-by, late charges included, skipping n/a; a claim shared with others counts its
 * due less the holders' parts, each holder's due-total counting their part), and the sheet's
 * claimed-total, due-total and overdue-total (the last two leaving out the single highest and lowest
 * person's figure when three or more people have one). A change takes a snapshot before it alters anything, then
 * {@link #recordChanges} audits, after the change's own lines, each person whose figures moved (in the
 * order they were added) and then the sheet's totals when they moved, and then an ALERT for each person
 * whose due-total went from at or below the sheet's alert threshold (or none) to above it.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. The settled figures (of their counting SETTLED claims
     *  that correct nothing, skipping n/a) and how many counting claims are overdue make up their score. */
    record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal, BigDecimal settled,
            int overdueCount) {
        boolean sameAs(Figures other) {
            return other != null && same(claimed, other.claimed) && same(dueTotal, other.dueTotal)
                    && same(overdueTotal, other.overdueTotal);
        }
    }

    /** The sheet's figures at one moment: each person's, by id in the order they were added, and the totals. */
    public record Snapshot(String sheetId, Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final RateChangeRepository rateChanges;
    private final SheetRepository sheets;
    private final SheetSettingRepository settings;
    private final SheetSettingDatedRepository dated;
    private final SheetParentRepository parents;
    private final PersonSnapshotRepository snapshots;
    private final PoolRepository pools;
    private final CrewRepository crews;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates,
            RateChangeRepository rateChanges, SheetRepository sheets, SheetSettingRepository settings,
            SheetSettingDatedRepository dated, SheetParentRepository parents, PersonSnapshotRepository snapshots,
            PoolRepository pools, CrewRepository crews, Audit audit) {
        this.pools = pools;
        this.crews = crews;
        this.rateChanges = rateChanges;
        this.snapshots = snapshots;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.settings = settings;
        this.dated = dated;
        this.parents = parents;
        this.audit = audit;
    }

    /** Each person's cut total on the sheet as the store holds it now, by person id: their cuts across every
     *  pool and crew they are a member of, the sheet's today as it is; none for a person with no cut. */
    public Map<Long, BigDecimal> cutTotals(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        return cutTotals(sheetId, people.findInListOrder(sheetId), today);
    }

    private Map<Long, BigDecimal> cutTotals(String sheetId, List<Person> listed, LocalDate today) {
        BigDecimal pot = settings.findBySheetIdAndKey(sheetId, SettingService.POT)
                .map((s) -> new BigDecimal(s.getValue()).setScale(2)).orElse(null);
        return PoolCuts.personTotals(pools.findBySheetIdOrderByIdAsc(sheetId), crews.findBySheetIdOrderByIdAsc(sheetId),
                listed, pot, today);
    }

    /** The sheet's figures as the store holds them now, within the caller's transaction. */
    public Snapshot snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        // The floor and late percent are those in force on each claim's date; the cap that in force today.
        DatedSettings values = DatedSettings.of(dated, sheetId);
        BigDecimal cap = values.capOn(today);
        LocalDate policyDate = Rounding.policyDate(SettingService.policyDateOf(settings, sheetId), today);
        // Each grade's rates on the sheet, then on each of its parents in turn.
        List<String> lineage = SettingService.lineageOf(parents, sheetId);
        Map<Grade, List<List<Rate>>> ratesByGrade = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) {
            List<List<Rate>> levels = new ArrayList<>();
            for (int i = 0; i < lineage.size(); i++) levels.add(new ArrayList<>());
            ratesByGrade.put(grade, levels);
        }
        for (int level = 0; level < lineage.size(); level++) {
            for (Rate rate : rates.findBySheetIdInOrder(lineage.get(level))) {
                ratesByGrade.get(rate.getGrade()).get(level).add(rate);
            }
        }
        // A forward-only version of a rate applies only to the claims added after it was saved.
        boolean forwardOnly = ratesByGrade.values().stream().flatMap(List::stream).flatMap(List::stream)
                .anyMatch((r) -> r.getForwardAfter() != null);
        AppliedRates applied = forwardOnly ? AppliedRates.of(rateChanges, lineage) : null;
        Map<Long, List<Claim>> claimsByPerson = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            claimsByPerson.computeIfAbsent(claim.getPersonId(), p -> new ArrayList<>()).add(claim);
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        // The parts of shared claims each holder holds, and each person's due and whether a claim keeps
        // their figure, by person id: a due-total is made up once every claim's parts are known.
        Map<Long, BigDecimal> heldParts = new LinkedHashMap<>();
        Map<Long, BigDecimal> dueOf = new LinkedHashMap<>();
        Map<Long, Boolean> keptOf = new LinkedHashMap<>();
        List<Person> listed = people.findInListOrder(sheetId);
        for (Person person : listed) {
            Grade gradeToday = today == null ? null : person.gradeOn(today);
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            BigDecimal settled = ZERO;
            int overdueCount = 0;
            boolean keptFigure = false;
            List<Claim> theirs = claimsByPerson.getOrDefault(person.getId(), List.of());
            for (Claim claim : theirs) {
                if (!counts(person, claim, today)) continue;
                // A correction counts toward what is due, but not toward what is claimed.
                if (!claim.isCorrection()) claimed = claimed.add(claim.getAmount());
                // A claim's live figure is at the grade in force on its date.
                Grade grade = person.gradeOn(claim.getDate());
                LocalDate roundsBy = Rounding.dateOf(claim, corrected(claim, theirs));
                RoundingMode rounding = Rounding.of(roundsBy, policyDate, values.roundingOn(roundsBy));
                List<List<Rate>> gradeRates = ratesByGrade.getOrDefault(grade, List.of());
                if (applied != null) gradeRates = applied.appliedLevels(gradeRates, claim.getId());
                BigDecimal figure = figure(claim, gradeRates, rounding);
                int grace = values.graceOn(grade, claim.getDate());
                BigDecimal owed = LateCharge.dueOf(claim, figure, values.floorOn(claim.getDate()), today,
                        grace, values::latePctOn, rounding);
                if (owed != null) due = due.add(owed);
                // Each holder's part of what is owed is theirs, not the claim's own person's.
                if (owed != null) {
                    for (ClaimShare share : claim.getShares()) {
                        BigDecimal part = share.partOf(owed);
                        due = due.subtract(part);
                        heldParts.merge(share.getPersonId(), part, BigDecimal::add);
                    }
                }
                if (owed != null && isOverdue(claim, grace, today)) overdue = overdue.add(owed);
                if (isOverdue(claim, grace, today)) overdueCount++;
                if (owed != null && claim.getStatus() == ClaimStatus.SETTLED && !claim.isCorrection()) settled = settled.add(owed);
                if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) {
                    keptFigure = true;
                }
                // A live figure at a grade other than today's is not undone by today's grade having no rate.
                if (figure != null && grade != gradeToday) keptFigure = true;
                // Nor is a live figure on a claim dated by today undone by its rate having ended since.
                if (figure != null && today != null && !claim.getDate().isAfter(today)) keptFigure = true;
            }
            dueOf.put(person.getId(), due);
            keptOf.put(person.getId(), keptFigure);
            figures.put(person.getId(), new Figures(person.getName(), claimed, null, overdue, settled, overdueCount));
            claimedTotal = claimedTotal.add(claimed);
            overdues.add(overdue);
        }
        Map<Long, BigDecimal> cuts = cutTotals(sheetId, listed, today);
        for (Person person : listed) {
            Grade gradeToday = today == null ? null : person.gradeOn(today);
            BigDecimal part = heldParts.get(person.getId());
            BigDecimal due = part == null ? dueOf.get(person.getId()) : dueOf.get(person.getId()).add(part);
            // A part of a claim shared with the person keeps a figure.
            boolean keptFigure = keptOf.get(person.getId()) || part != null;
            // n/a when no rate of the grade in force today is in force today and no claim keeps a figure.
            // Held to the sheet's cap less their cut total; n/a is not capped.
            BigDecimal personDue = SettingService.capped((today == null
                    || Rate.inForceInherited(ratesByGrade.getOrDefault(gradeToday, List.of()), today) == null) && !keptFigure
                            ? null : due, cap, cuts.get(person.getId()));
            Figures was = figures.get(person.getId());
            figures.put(person.getId(), new Figures(was.name(), was.claimed(), personDue, was.overdueTotal(),
                    was.settled(), was.overdueCount()));
            if (personDue != null) dues.add(personDue);
        }
        return new Snapshot(sheetId, figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /** How many of the sheet's claims are overdue and count, over everyone on it, as the store holds them
     *  now, within the caller's transaction. */
    public int overdueCount(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        DatedSettings values = DatedSettings.of(dated, sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : people.findInListOrder(sheetId)) byId.put(person.getId(), person);
        int count = 0;
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !counts(person, claim, today)) continue;
            int grace = values.graceOn(person.gradeOn(claim.getDate()), claim.getDate());
            if (isOverdue(claim, grace, today)) count++;
        }
        return count;
    }

    /** The person's score, as the screen shows it: their settled figures (of SETTLED claims that correct
     *  nothing) divided by 100, less 5 for each
     *  overdue claim that counts, rounded half to even to a whole number. */
    static String score(Figures figures) {
        return figures.settled().movePointLeft(2).subtract(BigDecimal.valueOf(5L * figures.overdueCount()))
                .setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** While the sheet's score log is on, audit once the transaction commits each person's score as it
     *  stood in the snapshot, in the order they were added: the day being left. Then keep, and audit once the transaction commits, each person's due-total as it stood in the snapshot,
     *  in the order they were added, against the sheet's today: the day being left. */
    public void recordSnapshot(String user, Snapshot before) {
        String prefix = " sheet=" + before.sheetId() + " by=" + user;
        LocalDate day = sheets.findById(before.sheetId()).map(Sheet::getToday).orElse(null);
        if (SettingService.scoreLogOf(settings, before.sheetId())) {
            before.people().values().forEach(figures -> audit.recordOnCommit("SCORE" + prefix + " name="
                    + figures.name() + " score=" + score(figures)));
        }
        before.people().forEach((id, figures) -> {
            if (day != null) snapshots.save(new PersonSnapshot(before.sheetId(), id, day, figures.dueTotal()));
            audit.recordOnCommit("SNAPSHOT" + prefix + " name=" + figures.name() + " due-total="
                    + text(figures.dueTotal()));
        });
    }

    /** The lines of each person's snapshots, by person id, oldest first: the day being left and the
     *  due-total then. */
    public Map<Long, List<String>> snapshotLines(String sheetId) {
        Map<Long, List<String>> lines = new LinkedHashMap<>();
        for (PersonSnapshot s : snapshots.findBySheetIdOrderByIdAsc(sheetId)) {
            lines.computeIfAbsent(s.getPersonId(), p -> new ArrayList<>()).add(snapshotLine(s));
        }
        return lines;
    }

    /** The lines of the person's snapshots, oldest first. */
    public List<String> snapshotLinesOf(Long personId) {
        return snapshots.findByPersonIdOrderByIdAsc(personId).stream().map(Rollup::snapshotLine).toList();
    }

    private static String snapshotLine(PersonSnapshot s) {
        return s.getDay() + " " + text(s.getDueTotal());
    }

    /** Audit, once the transaction commits, the figures that moved since the snapshot was taken. */
    public void recordChanges(String user, Snapshot before) {
        Snapshot after = snapshot(before.sheetId());
        String prefix = " sheet=" + before.sheetId() + " by=" + user;
        after.people().forEach((id, now) -> {
            if (!now.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + text(now.claimed())
                        + " due-total=" + text(now.dueTotal()) + " overdue-total=" + text(now.overdueTotal()));
            }
        });
        if (!same(before.claimedTotal(), after.claimedTotal()) || !same(before.dueTotal(), after.dueTotal())
                || !same(before.overdueTotal(), after.overdueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + text(after.claimedTotal())
                    + " due-total=" + text(after.dueTotal()) + " overdue-total=" + text(after.overdueTotal()));
        }
        BigDecimal threshold = SettingService.alertThresholdOf(settings, before.sheetId());
        if (threshold == null) return;
        after.people().forEach((id, now) -> {
            Figures was = before.people().get(id);
            if (above(now.dueTotal(), threshold) && (was == null || !above(was.dueTotal(), threshold))) {
                audit.recordOnCommit("ALERT" + prefix + " name=" + now.name() + " due-total=" + text(now.dueTotal()));
            }
        });
    }

    /** Whether a due-total is above the threshold; none is not. */
    private static boolean above(BigDecimal dueTotal, BigDecimal threshold) {
        return dueTotal != null && dueTotal.compareTo(threshold) > 0;
    }

    /** Audit, once the transaction commits, every person's figures, in the order they were added, and then
     *  the sheet's totals, as the store holds them now. */
    public void recordAll(String user, String sheetId) {
        Snapshot now = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        now.people().values().forEach(figures -> audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name()
                + " claimed=" + text(figures.claimed()) + " due-total=" + text(figures.dueTotal())
                + " overdue-total=" + text(figures.overdueTotal())));
        audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + text(now.claimedTotal())
                + " due-total=" + text(now.dueTotal()) + " overdue-total=" + text(now.overdueTotal()));
    }

    /** Whether the claim is overdue: open and not deferred, with its due-by plus the grace days of its
     *  person's grade before the sheet's today. */
    private static boolean isOverdue(Claim claim, int grace, LocalDate today) {
        return claim.getStatus().isOpen() && !claim.isDeferredOn(today) && today != null
                && LateCharge.lateFrom(claim, grace).isBefore(today);
    }

    /** Only open (PENDING included) and settled claims count toward what is claimed and due; a person's
     *  open claims, once their retirement date is reached, do not, unless a statement binds them (a hold,
     *  the person's or the claim's, leaves them in); nor does a
     *  claim deferred on the sheet's today. */
    private static boolean counts(Person person, Claim claim, LocalDate today) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus().isOpen() && !claim.isDeferredOn(today)
                        && (claim.isBound() || !person.isRetiredBy(today)));
    }

    /** The claim's figure, as ClaimService.figure works it out: the settled due, else the stated due
     *  (PENDING included), else the rate figure at the grade's rate in force on its date (the sheet's, else its nearest parent's) of the
     *  versions that apply to the claim, rounded as the claim rounds,
     *  before any late charge and floor; null when that is n/a. */
    private static BigDecimal figure(Claim claim, List<List<Rate>> gradeRates, RoundingMode rounding) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        Rate rate = Rate.inForceInherited(gradeRates, claim.getDate());
        if (rate == null) return null;
        return claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2, rounding);
    }

    /** Of the person's claims, the one a correction corrects by number; null for any other claim. */
    private static Claim corrected(Claim claim, List<Claim> theirs) {
        if (!claim.isCorrection() || claim.getCorrects() == null) return null;
        for (Claim other : theirs) {
            if (claim.getCorrects().equals(other.getNumber())) return other;
        }
        return null;
    }

    /** The sum of the figures, leaving out the single highest and the single lowest when there are three or more. */
    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal total = figures.stream().reduce(ZERO, BigDecimal::add);
        if (figures.size() < 3) return total;
        return total.subtract(figures.stream().max(BigDecimal::compareTo).get())
                .subtract(figures.stream().min(BigDecimal::compareTo).get());
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static String text(BigDecimal figure) {
        return figure == null ? "n/a" : figure.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
