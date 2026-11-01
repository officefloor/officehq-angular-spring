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
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the
 * sheet's claimed-total and due-total, each person's at most the sheet's cap in force today; a claim's floor and late percent are those in force on its date; an overdue claim's due has its {@link LateCharge} on top. A change takes a {@link #snapshot} before it alters
 * anything and calls {@link #recordChanges} once it has, which audits, after the change's own
 * lines, a ROLLUP line for each person whose figures (overdue-total included) moved (in the order
 * they were added) and a TOTALS line when the sheet's totals moved.
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

    /** The sheet's figures at a moment: each person's, by id in the order they were added, and the totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {}

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

    /**
     * The sheet's figures now. Locks the sheet until the caller's transaction ends, so no other
     * change on the sheet moves the figures between this snapshot and the one after the change.
     */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheets.findForUpdate(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        LocalDate policyDate = sheet == null ? null : sheet.getPolicyDate();
        Map<String, List<Rate>> ratesByGrade = new LinkedHashMap<>();
        for (Rate rate : rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId)) {
            ratesByGrade.computeIfAbsent(rate.getGrade(), g -> new ArrayList<>()).add(rate);
        }
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
        BigDecimal dueTotal = zero();
        BigDecimal overdueTotal = zero();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean fixed = false;
            for (Claim claim : claimsByPerson.getOrDefault(person.getId(), List.of())) {
                if (!person.counts(claim, today)) continue;
                claimed = claimed.add(claim.getAmount());
                if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) fixed = true;
                String grade = person.gradeOn(claim.getDate());
                RoundingMode rounding = Rounding.of(Rounding.basisOf(claim, datesByNumber), policyDate, today);
                // the floor and late percent in force on the claim's date
                BigDecimal floor = sheet == null ? zero() : sheet.floorOn(claim.getDate());
                BigDecimal latePct = sheet == null ? zero() : sheet.latePctOn(claim.getDate());
                BigDecimal figure = LateCharge.withCharge(claim,
                        figureOf(claim, ratesByGrade.getOrDefault(grade, List.of()), floor, rounding), today,
                        latePct, rounding);
                // a due under a grade they had on its date but not today counts like a fixed one
                if (figure != null && today != null && !grade.equals(person.gradeOn(today))) fixed = true;
                // and so does a due under a rate that is not the one in force today
                if (figure != null && today != null && !Rate.inForce(ratesByGrade.getOrDefault(grade, List.of()),
                        claim.getDate()).equals(Rate.inForce(ratesByGrade.getOrDefault(grade, List.of()), today))) fixed = true;
                if (figure != null) due = due.add(figure);
                if (figure != null && LateCharge.isOverdue(claim, today)) overdue = overdue.add(figure);
            }
            // As on screen: n/a when their grade has no rate today and none of the counted claims has a fixed
            // due, nor a due under another grade they had on its date or under a rate not in force today.
            boolean rated = today != null
                    && pctOn(ratesByGrade.getOrDefault(person.gradeOn(today), List.of()), today) != null;
            BigDecimal personDue = fixed || rated ? due : null;
            if (sheet != null) personDue = sheet.capped(personDue);
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue, overdue));
            claimedTotal = claimedTotal.add(claimed);
            if (personDue != null) dueTotal = dueTotal.add(personDue);
            overdueTotal = overdueTotal.add(overdue);
        }
        return new Snapshot(figures, claimedTotal, dueTotal, overdueTotal);
    }

    /** Audit, once the transaction commits, how the sheet's figures moved since {@code before}. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        String by = " sheet=" + sheetId + " by=" + user.strip();
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures now = e.getValue();
            if (now.sameAs(before.people().get(e.getKey()))) continue;
            audit.recordOnCommit("ROLLUP" + by + " person=" + now.name() + " claimed=" + money(now.claimed())
                    + " due-total=" + (now.dueTotal() == null ? "n/a" : money(now.dueTotal()))
                    + " overdue-total=" + money(now.overdueTotal()));
        }
        if (after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0
                || after.overdueTotal().compareTo(before.overdueTotal()) != 0) {
            audit.recordOnCommit("TOTALS" + by + " claimed-total=" + money(after.claimedTotal())
                    + " due-total=" + money(after.dueTotal())
                    + " overdue-total=" + money(after.overdueTotal()));
        }
    }

    /**
     * A claim's figure: its settled due, else its stated due, else its live due, at least the floor
     * unless a correction, rounded as given; null when it has none.
     */
    private static BigDecimal figureOf(Claim claim, List<Rate> rates, BigDecimal floor, RoundingMode rounding) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        BigDecimal pct = pctOn(rates, claim.getDate());
        if (pct == null) return null;
        BigDecimal due = claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding);
        return claim.isCorrection() ? due : due.max(floor);
    }

    /** Of one grade's rates, the percent in force on the date, or null. */
    private static BigDecimal pctOn(List<Rate> rates, LocalDate date) {
        return Rate.inForce(rates, date)
                .map(Rate::getPct)
                .orElse(null);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
