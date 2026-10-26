package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The figures a sheet shows: each person's claimed and due-total, and the sheet's totals. A change takes a
 * {@link #snapshot} before it and calls {@link #recordChanges} after it, so the audit gets one ROLLUP line per person
 * whose figures (claimed, due-total, overdue-total) moved (in the order they were added) and a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /** One person's figures; a null due-total is n/a. The overdue-total sums their overdue claims' dues. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The figures of every person on a sheet, by person id in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now (as the screen computes them). */
    public Snapshot snapshot(String sheetId) {
        claims.flush();
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<String, List<Rate>> byGrade = new HashMap<>();
        for (Rate r : rates.findBySheetIdOrderByGradeAscFromAsc(sheetId)) {
            byGrade.computeIfAbsent(r.getGrade(), g -> new ArrayList<>()).add(r);
        }
        Map<Long, List<Claim>> claimsBy = new HashMap<>();
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (c.getStatus().counts()) {
                claimsBy.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        BigDecimal dueTotal = ZERO;
        BigDecimal overdueSum = ZERO;
        for (Person p : people.findBySheetIdOrderByIdAsc(sheetId)) {
            // an open claim dated within one of the person's holds counts in no live total, unless a statement binds it;
            // nor does one deferred on the sheet's today
            List<Claim> counted = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsLive(c, today)).toList();
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            BigDecimal overdue = ZERO;
            boolean kept = false;
            // the grades whose rate today decides n/a: the one in force today and each counted claim's
            Set<String> grades = new HashSet<>();
            grades.add(p.gradeOn(today));
            for (Claim c : counted) {
                grades.add(p.gradeOn(c.getDate()));
                claimed = claimed.add(c.getAmount());
                kept |= c.getSettledDue() != null || (c.isBound() && c.getStatedDue() != null);
                // the claim's rate is the one its person's grade in force on its date has
                BigDecimal figure = figure(c, byGrade.getOrDefault(p.gradeOn(c.getDate()), List.of()), sheet);
                if (c.getStatus().isOpen() && !c.isCorrection()) {
                    figure = ClaimService.withLateCharge(figure, c, sheet);
                }
                if (figure != null) {
                    kept = true;
                    due = due.add(figure);
                    if (isOverdue(c, today)) {
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
            overdueSum = overdueSum.add(overdue);
            if (due != null) {
                dueTotal = dueTotal.add(due);
            }
        }
        return new Snapshot(figures, claimedTotal, dueTotal, overdueSum);
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
    }

    /**
     * The figure a claim totals: its settled due, else the one a statement bound it to, else live (floored at the
     * floor in force on its date, unless it is a correction), rounded as the sheet's policy date decides for it.
     */
    private static BigDecimal figure(Claim c, List<Rate> rates, Sheet sheet) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        if (c.isBound()) {
            return c.getStatedDue();
        }
        Rate rate = Rate.inForce(rates, c.getDate());
        return rate == null ? null : ClaimService.floored(ClaimService.dueOn(c.getAmount(), rate.getPct(), sheet.roundingOn(c.getRoundsOn())),
                c.isCorrection() ? null : sheet.floorOn(c.getDate()));
    }

    /** Whether a claim is overdue: open (or pending), not deferred, and due by a date before the sheet's today. */
    private static boolean isOverdue(Claim c, LocalDate today) {
        return c.getStatus().isOpen() && today != null && !c.isDeferredOn(today) && c.getDueBy() != null
                && c.getDueBy().isBefore(today);
    }

    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
