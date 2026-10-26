package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
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
 * whose figures moved (in the order they were added) and a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /** One person's figures; a null due-total is n/a. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal) {
    }

    /** The figures of every person on a sheet, by person id in the order they were added, and the sheet's totals. */
    public record Snapshot(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal) {
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
        BigDecimal floor = sheet == null ? null : sheet.getFloor();
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
        for (Person p : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Rate> list = byGrade.getOrDefault(p.getGrade(), List.of());
            // while the person is held, their open claims count in no live total
            List<Claim> counted = claimsBy.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> p.countsLive(c.getStatus())).toList();
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            boolean kept = false;
            for (Claim c : counted) {
                claimed = claimed.add(c.getAmount());
                kept |= c.getSettledDue() != null || (c.isStated() && c.getStatedDue() != null);
                BigDecimal figure = figure(c, list, floor);
                if (c.getStatus() == ClaimStatus.OPEN) {
                    figure = ClaimService.withLateCharge(figure, c.getDate(), sheet);
                }
                if (figure != null) {
                    due = due.add(figure);
                }
            }
            // with no rate in force today for their grade and no kept figure, the due-total is n/a
            if (!kept && (today == null || inForce(list, today) == null)) {
                due = null;
            }
            figures.put(p.getId(), new Figures(p.getName(), claimed, due));
            claimedTotal = claimedTotal.add(claimed);
            if (due != null) {
                dueTotal = dueTotal.add(due);
            }
        }
        return new Snapshot(figures, claimedTotal, dueTotal);
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
            if (was != null && was.claimed().equals(now.claimed()) && Objects.equals(was.dueTotal(), now.dueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP" + head + " person=" + now.name() + " claimed=" + now.claimed().toPlainString()
                    + " due-total=" + text(now.dueTotal()));
        }
        if (!before.claimedTotal().equals(after.claimedTotal()) || !before.dueTotal().equals(after.dueTotal())) {
            audit.recordOnCommit("TOTALS" + head + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString());
        }
    }

    /** The figure a claim totals: its settled due, else the one a statement bound it to, else live (floored). */
    private static BigDecimal figure(Claim c, List<Rate> rates, BigDecimal floor) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        if (c.isStated()) {
            return c.getStatedDue();
        }
        Rate rate = inForce(rates, c.getDate());
        return rate == null ? null : ClaimService.floored(ClaimService.dueOn(c.getAmount(), rate.getPct()), floor);
    }

    /** The rate in force on the date: the one with the latest from-date on or before it; null with none. */
    private static Rate inForce(List<Rate> rates, LocalDate date) {
        return rates.stream().filter(r -> r.getFrom() == null || !r.getFrom().isAfter(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
