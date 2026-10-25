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
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
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
 * included, held to the sheet's cap) and overdue-total (what is owed on their counting claims that are
 * open past their due-by, late charges included, skipping n/a), and the sheet's
 * claimed-total, due-total and overdue-total (the last two leaving out the single highest and lowest
 * person's figure when three or more people have one). A change takes a snapshot before it alters anything, then
 * {@link #recordChanges} audits, after the change's own lines, each person whose figures moved (in the
 * order they were added) and then the sheet's totals when they moved.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. */
    record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
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
    private final SheetRepository sheets;
    private final SheetSettingRepository settings;
    private final SheetSettingDatedRepository dated;
    private final SheetParentRepository parents;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            SheetSettingRepository settings, SheetSettingDatedRepository dated, SheetParentRepository parents,
            Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.settings = settings;
        this.dated = dated;
        this.parents = parents;
        this.audit = audit;
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
        Map<Long, List<Claim>> claimsByPerson = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            claimsByPerson.computeIfAbsent(claim.getPersonId(), p -> new ArrayList<>()).add(claim);
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            Grade gradeToday = today == null ? null : person.gradeOn(today);
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            boolean keptFigure = false;
            List<Claim> theirs = claimsByPerson.getOrDefault(person.getId(), List.of());
            for (Claim claim : theirs) {
                if (!counts(person, claim, today)) continue;
                claimed = claimed.add(claim.getAmount());
                // A claim's live figure is at the grade in force on its date.
                Grade grade = person.gradeOn(claim.getDate());
                RoundingMode rounding = Rounding.of(Rounding.dateOf(claim, corrected(claim, theirs)), policyDate);
                BigDecimal figure = figure(claim, ratesByGrade.getOrDefault(grade, List.of()), rounding);
                int grace = values.graceOn(grade, claim.getDate());
                BigDecimal owed = LateCharge.dueOf(claim, figure, values.floorOn(claim.getDate()), today,
                        grace, values::latePctOn, rounding);
                if (owed != null) due = due.add(owed);
                if (owed != null && isOverdue(claim, grace, today)) overdue = overdue.add(owed);
                if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) {
                    keptFigure = true;
                }
                // A live figure at a grade other than today's is not undone by today's grade having no rate.
                if (figure != null && grade != gradeToday) keptFigure = true;
                // Nor is a live figure on a claim dated by today undone by its rate having ended since.
                if (figure != null && today != null && !claim.getDate().isAfter(today)) keptFigure = true;
            }
            // n/a when no rate of the grade in force today is in force today and no claim keeps a figure.
            // Held to the sheet's cap; n/a is not capped.
            BigDecimal personDue = SettingService.capped((today == null
                    || Rate.inForceInherited(ratesByGrade.getOrDefault(gradeToday, List.of()), today) == null) && !keptFigure
                            ? null : due, cap);
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue, overdue));
            claimedTotal = claimedTotal.add(claimed);
            if (personDue != null) dues.add(personDue);
            overdues.add(overdue);
        }
        return new Snapshot(sheetId, figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /** Audit, once the transaction commits, each person's due-total as it stood in the snapshot, in the
     *  order they were added. */
    public void recordSnapshot(String user, Snapshot before) {
        String prefix = " sheet=" + before.sheetId() + " by=" + user;
        before.people().values().forEach(figures -> audit.recordOnCommit("SNAPSHOT" + prefix + " name="
                + figures.name() + " due-total=" + text(figures.dueTotal())));
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
    }

    /** Whether the claim is overdue: open and not deferred, with its due-by plus the grace days of its
     *  person's grade before the sheet's today. */
    private static boolean isOverdue(Claim claim, int grace, LocalDate today) {
        return claim.getStatus().isOpen() && !claim.isDeferredOn(today) && today != null
                && LateCharge.lateFrom(claim, grace).isBefore(today);
    }

    /** Only open (PENDING included) and settled claims count toward what is claimed and due; a person's
     *  open claims dated within one of their holds, or all of them once their retirement date is reached,
     *  do not, unless a statement binds them; nor does a
     *  claim deferred on the sheet's today. */
    private static boolean counts(Person person, Claim claim, LocalDate today) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus().isOpen() && !claim.isDeferredOn(today)
                        && (claim.isBound() || !person.isLeftOut(claim.getDate(), today)));
    }

    /** The claim's figure, as ClaimService.figure works it out: the settled due, else the stated due,
     *  (unless PENDING), else the rate figure at the grade's rate in force on its date (the sheet's, else its nearest parent's), rounded as the claim rounds,
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
