package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: what each person has claimed, their due-total and
 * overdue-total, and the sheet's claimed-total, due-total and overdue-total. A change takes them
 * before it writes and has them audited after its own records, so every figure it moves is recorded.
 */
@Service
public class Rollups {

    /** Shown in place of a due-total when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    /**
     * A person's figures: what they have claimed, their due-total (null when n/a) and their
     * overdue-total (the due of their counting overdue claims).
     */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {

        boolean sameAs(Figures other) {
            return other != null && same(claimed, other.claimed) && same(dueTotal, other.dueTotal)
                    && same(overdueTotal, other.overdueTotal);
        }
    }

    /** The sheet's figures: each person's by id, in the order they were added, and the sheet's totals. */
    public record Rollup(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Dues dues;
    private final Audit audit;

    public Rollups(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            Dues dues, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.dues = dues;
        this.audit = audit;
    }

    /** The sheet's figures as they stand now in the current transaction. */
    public Rollup take(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, List<Claim>> byPerson = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus().counts()) {
                byPerson.computeIfAbsent(claim.getPersonId(), id -> new ArrayList<>()).add(claim);
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> counting = byPerson.getOrDefault(person.getId(), List.of()).stream()
                    .filter(claim -> person.countsLive(claim.getStatus(), claim.getDate(), claim.isStated(), claim.isDeferredOn(today)))
                    .toList();
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean figured = false;
            for (Claim claim : counting) {
                claimed = claimed.add(claim.getAmount());
                BigDecimal figure = dues.due(claim, person.gradeOn(claim.getDate()));
                if (figure != null) {
                    due = due.add(figure);
                    figured = true;
                    if (overdue(claim, today)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            // As on the screen: n/a when the grade in force today has no rate, unless a claim has a
            // figure (a fixed one, or one at the grade in force on its own date).
            boolean rated = today != null && rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
            if (!rated && !figured) {
                due = null;
            }
            // A due-total is at most the sheet's cap.
            if (sheet != null) {
                due = sheet.capped(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), claimed, due, overdue));
            claimedTotal = claimedTotal.add(claimed);
            if (due != null) {
                dueFigures.add(due);
            }
            overdueFigures.add(overdue);
        }
        return new Rollup(figures, claimedTotal, trimmed(dueFigures), trimmed(overdueFigures));
    }

    /**
     * The sum of the people's figures, leaving out the single highest and the single lowest when there
     * are three or more.
     */
    static BigDecimal trimmed(List<BigDecimal> figures) {
        BigDecimal total = zero();
        for (BigDecimal figure : figures) {
            total = total.add(figure);
        }
        if (figures.size() >= 3) {
            BigDecimal highest = figures.stream().max(BigDecimal::compareTo).orElseThrow();
            BigDecimal lowest = figures.stream().min(BigDecimal::compareTo).orElseThrow();
            total = total.subtract(highest).subtract(lowest);
        }
        return total;
    }

    /**
     * Record, once the change commits, one ROLLUP line per person whose figures moved since
     * {@code before} (a person new since then counts as moved), then a TOTALS line when any of the sheet's
     * totals moved.
     */
    public void audit(String sheetId, String user, Rollup before) {
        Rollup after = take(sheetId);
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed().toPlainString() + " due-total=" + shown(figures.dueTotal())
                        + " overdue-total=" + figures.overdueTotal().toPlainString());
            }
        });
        if (!same(after.claimedTotal(), before.claimedTotal()) || !same(after.dueTotal(), before.dueTotal())
                || !same(after.overdueTotal(), before.overdueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user
                    + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString()
                    + " overdue-total=" + after.overdueTotal().toPlainString());
        }
    }

    /**
     * Whether a claim is overdue: OPEN or PENDING with its due-by before the sheet's today, and not
     * deferred on it.
     */
    private static boolean overdue(Claim claim, LocalDate today) {
        return today != null && claim.getStatus().isOpen() && claim.getDueBy().isBefore(today)
                && !claim.isDeferredOn(today);
    }

    /** Whether two figures are the same amount, or both n/a. */
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String shown(BigDecimal due) {
        return due != null ? due.toPlainString() : NO_RATE;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
