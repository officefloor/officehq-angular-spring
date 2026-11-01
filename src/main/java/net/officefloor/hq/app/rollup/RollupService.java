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
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.InheritedRates;
import net.officefloor.hq.app.rate.RateLevels;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the
 * sheet's claimed-total and due-total, each person's at most the sheet's cap in force today; a claim's floor is the one in force on its date, its late percent the one in force on each 30-day boundary; an overdue claim's due has its {@link LateCharge} on top. A change takes a {@link #snapshot} before it alters
 * anything and calls {@link #recordChanges} once it has, which audits, after the change's own
 * lines, a ROLLUP line for each person whose figures (overdue-total included) moved (in the order
 * they were added) and a TOTALS line when the sheet's totals moved, then, while the sheet has an alert
 * threshold, an ALERT line for each person (in list order) whose due-total went from at or below it (or
 * none) to above it.
 */
@Service
public class RollupService {

    /** A person's figures; a null due-total is n/a; the overdue-total is the dues of their overdue claims. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0 && Objects.equals(dueTotal, other.dueTotal)
                    && overdueTotal.compareTo(other.overdueTotal) == 0;
        }
    }

    /** The sheet's figures at a moment: each person's, in list order (by position), and the totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {}

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final InheritedRates rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, InheritedRates rates,
            SheetRepository sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /**
     * The sheet's figures now. Locks the sheet until the caller's transaction ends, so no other
     * change on the sheet moves the figures between this snapshot and the one after the change.
     */
    public Snapshot snapshot(String sheetId) {
        return figuresOf(sheetId, sheets.findForUpdate(sheetId).orElse(null));
    }

    /** The sheet's figures now, as it shows them, without locking it. */
    @Transactional(readOnly = true)
    public Snapshot current(String sheetId) {
        return figuresOf(sheetId, sheets.findById(sheetId).orElse(null));
    }

    private Snapshot figuresOf(String sheetId, Sheet sheet) {
        LocalDate today = sheet == null ? null : sheet.getToday();
        LocalDate policyDate = sheet == null ? null : sheet.getPolicyDate();
        // its own rates, then its ancestors' for a grade and date it has none in force on
        RateLevels levels = rates.of(sheetId);
        Map<Long, List<Claim>> claimsByPerson = new LinkedHashMap<>();
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<String, LocalDate> datesByNumber = Rounding.datesByNumber(all);
        for (Claim claim : all) {
            if (claim.getStatus().counts()) {
                claimsByPerson.computeIfAbsent(claim.getPersonId(), id -> new ArrayList<>()).add(claim);
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean fixed = false;
            for (Claim claim : claimsByPerson.getOrDefault(person.getId(), List.of())) {
                if (!person.counts(claim, today)) continue;
                // a correction counts toward due but not toward what is claimed
                if (!claim.isCorrection()) claimed = claimed.add(claim.getAmount());
                if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) fixed = true;
                String grade = person.gradeOn(claim.getDate());
                RoundingMode rounding = Rounding.of(Rounding.basisOf(claim, datesByNumber), policyDate, today);
                // the floor in force on the claim's date, the late percent in force on each 30-day boundary
                BigDecimal floor = sheet == null ? zero() : sheet.floorOn(claim.getDate());
                // and the grace days of their grade on the claim's date, in force on it
                int grace = sheet == null ? 0 : sheet.graceOn(grade, claim.getDate());
                BigDecimal figure = LateCharge.withCharge(claim,
                        figureOf(claim, levels, grade, rounding), today, grace,
                        d -> sheet == null ? zero() : sheet.latePctOn(d), rounding);
                // the floor last, over the figure and its charge; a settled due is frozen, a correction has none
                if (figure != null && claim.getSettledDue() == null && !claim.isCorrection()) figure = figure.max(floor);
                // a due under a grade they had on its date but not today counts like a fixed one
                if (figure != null && today != null && !grade.equals(person.gradeOn(today))) fixed = true;
                // and so does a due under a rate that is not the one in force today
                if (figure != null && today != null
                        && !levels.inForce(grade, claim.getDate()).equals(levels.inForce(grade, today))) fixed = true;
                if (figure != null) due = due.add(figure);
                if (figure != null && LateCharge.isOverdue(claim, today, grace)) overdue = overdue.add(figure);
            }
            // As on screen: n/a when their grade has no rate today and none of the counted claims has a fixed
            // due, nor a due under another grade they had on its date or under a rate not in force today.
            boolean rated = today != null && levels.inForce(person.gradeOn(today), today).isPresent();
            BigDecimal personDue = fixed || rated ? due : null;
            if (sheet != null) personDue = sheet.capped(personDue);
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue, overdue));
            claimedTotal = claimedTotal.add(claimed);
            if (personDue != null) dues.add(personDue);
            overdues.add(overdue);
        }
        return new Snapshot(figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
        if (figures.size() < 3) return sum;
        BigDecimal max = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(max).subtract(min);
    }

    /**
     * Audit, once the transaction commits, a SNAPSHOT line of each person's due-total in {@code snapshot}, in
     * list order, and keep it on the person with the day being left.
     */
    public void recordSnapshot(String sheetId, String user, LocalDate dayLeft, Snapshot snapshot) {
        String by = " sheet=" + sheetId + " by=" + user.strip();
        for (Map.Entry<Long, Figures> e : snapshot.people().entrySet()) {
            Figures figures = e.getValue();
            people.findById(e.getKey()).ifPresent(p -> p.addSnapshot(dayLeft, figures.dueTotal()));
            audit.recordOnCommit("SNAPSHOT" + by + " name=" + figures.name()
                    + " due-total=" + (figures.dueTotal() == null ? "n/a" : money(figures.dueTotal())));
        }
    }

    /**
     * Audit, once the transaction commits, how the sheet's figures moved since {@code before}; with none
     * (null), every person's figures and the totals.
     */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        String by = " sheet=" + sheetId + " by=" + user.strip();
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures now = e.getValue();
            if (before != null && now.sameAs(before.people().get(e.getKey()))) continue;
            audit.recordOnCommit("ROLLUP" + by + " person=" + now.name() + " claimed=" + money(now.claimed())
                    + " due-total=" + (now.dueTotal() == null ? "n/a" : money(now.dueTotal()))
                    + " overdue-total=" + money(now.overdueTotal()));
        }
        if (before == null || after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0
                || after.overdueTotal().compareTo(before.overdueTotal()) != 0) {
            audit.recordOnCommit("TOTALS" + by + " claimed-total=" + money(after.claimedTotal())
                    + " due-total=" + money(after.dueTotal())
                    + " overdue-total=" + money(after.overdueTotal()));
        }
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) return;
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures now = e.getValue();
            Figures was = before == null ? null : before.people().get(e.getKey());
            if (above(now, threshold) && !above(was, threshold)) {
                audit.recordOnCommit("ALERT" + by + " name=" + now.name() + " due-total=" + money(now.dueTotal()));
            }
        }
    }

    /** Whether the person's due-total is above the threshold; none (or no figures) is not. */
    private static boolean above(Figures figures, BigDecimal threshold) {
        return figures != null && figures.dueTotal() != null && figures.dueTotal().compareTo(threshold) > 0;
    }

    /**
     * A claim's figure: its settled due, else its stated rate figure, else its rate figure (no floor),
     * rounded as given; null when it has none.
     */
    private static BigDecimal figureOf(Claim claim, RateLevels levels, String grade, RoundingMode rounding) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        BigDecimal pct = levels.inForce(grade, claim.getDate()).map(Rate::getPct).orElse(null);
        if (pct == null) return null;
        return claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
