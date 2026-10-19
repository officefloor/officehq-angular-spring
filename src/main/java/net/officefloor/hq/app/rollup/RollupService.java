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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the sheet's
 * claimed-total and due-total. A change takes them before it alters anything and hands them back after, and
 * every figure it moved is written to the audit after the change's own lines. Called within the change's
 * transaction.
 */
@Service
public class RollupService {

    /** Shown in place of a due-total whose grade has no rate. */
    private static final String NO_RATE = "n/a";

    /** A person's figures, as the screen shows them. */
    public record Figures(String name, String claimed, String dueTotal) {}

    /** The sheet's figures at one moment: each person's by id, in the order they were added, and the totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal) {}

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

    /** The sheet's figures now; none (an empty sheet's) when there is no such sheet. */
    public Snapshot snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(s -> s.getToday()).orElse(null);
        BigDecimal floor = floorOf(sheetId);
        Map<Long, List<Claim>> byPerson = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            byPerson.computeIfAbsent(claim.getPersonId(), k -> new ArrayList<>()).add(claim);
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal dueTotal = BigDecimal.ZERO.setScale(2);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Rate> gradeRates = rates.findBySheetIdAndGrade(sheetId, person.getGrade());
            List<Claim> counted = byPerson.getOrDefault(person.getId(), List.of()).stream()
                    .filter(c -> counts(c, person)).toList();
            BigDecimal claimed = BigDecimal.ZERO.setScale(2);
            BigDecimal due = BigDecimal.ZERO.setScale(2);
            boolean frozen = false;
            for (Claim claim : counted) {
                claimed = claimed.add(claim.getAmount().setScale(2));
                frozen |= keepsFigure(claim);
                BigDecimal figure = figureOf(claim, gradeRates, floor);
                if (figure != null) {
                    due = due.add(figure.setScale(2));
                }
            }
            // Like the screen: with no rate in force today and no claim keeping a figure, the due-total is n/a.
            boolean rated = today != null && Rate.inForce(gradeRates, today).isPresent();
            String personDue = rated || frozen ? due.toPlainString() : NO_RATE;
            claimedTotal = claimedTotal.add(claimed);
            if (rated || frozen) {
                dueTotal = dueTotal.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), claimed.toPlainString(), personDue));
        }
        return new Snapshot(figures, claimedTotal.toPlainString(), dueTotal.toPlainString());
    }

    /** The due-total of the sheet's claims that count (a held person's OPEN ones do not) dated from and to, inclusive: the sum of their figures,
     * n/a ones skipped. */
    public BigDecimal dueTotal(String sheetId, LocalDate from, LocalDate to) {
        BigDecimal floor = floorOf(sheetId);
        Map<Long, List<Rate>> gradeRates = new LinkedHashMap<>();
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            gradeRates.put(person.getId(), rates.findBySheetIdAndGrade(sheetId, person.getGrade()));
            byId.put(person.getId(), person);
        }
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            List<Rate> personRates = gradeRates.get(claim.getPersonId());
            if (personRates == null || !counts(claim, byId.get(claim.getPersonId()))
                    || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)) {
                continue;
            }
            BigDecimal figure = figureOf(claim, personRates, floor);
            if (figure != null) {
                total = total.add(figure.setScale(2));
            }
        }
        return total;
    }

    /**
     * Write to the audit, once the change commits, one ROLLUP line for each person whose figures moved since
     * {@code before} (a person new since then counts as moved), in the order they were added, then a TOTALS
     * line when the sheet's totals moved.
     */
    public void recordChanges(String user, String sheetId, Snapshot before) {
        claims.flush();
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures now = e.getValue();
            Figures was = before.people().get(e.getKey());
            if (was != null && was.claimed().equals(now.claimed()) && was.dueTotal().equals(now.dueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + now.claimed()
                    + " due-total=" + now.dueTotal());
        }
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal());
        }
    }

    /** The figure a claim has now: what it was settled at, else what its person's last statement bound it to,
     * else what is due at its grade's rate, raised to the sheet's floor; none when that is n/a. */
    public BigDecimal figureOf(Claim claim, String grade) {
        return figureOf(claim, rates.findBySheetIdAndGrade(claim.getSheetId(), grade), floorOf(claim.getSheetId()));
    }

    private static BigDecimal figureOf(Claim claim, List<Rate> gradeRates, BigDecimal floor) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : dueOf(claim, gradeRates, floor);
    }

    /** What is due on a claim at the rate its grade has in force on the claim's date, rounded half to even to two
     * decimals, and at least the floor; none when no rate of the grade applies then. */
    private static BigDecimal dueOf(Claim claim, List<Rate> gradeRates, BigDecimal floor) {
        BigDecimal amount = claim.getAmount();
        return Rate.inForce(gradeRates, claim.getDate())
                .map(r -> amount.multiply(r.getRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .map(due -> due.max(floor.setScale(2)))
                .orElse(null);
    }

    /** The sheet's floor; 1.00, the one a sheet starts with, when there is no such sheet. */
    private BigDecimal floorOf(String sheetId) {
        return sheets.findById(sheetId).map(s -> s.getFloor()).orElse(new BigDecimal("1.00"));
    }

    /** Whether a claim keeps a figure whatever the rates: settled at one, or bound to one (not n/a) by a
     * statement. */
    public static boolean keepsFigure(Claim claim) {
        return claim.getSettledDue() != null || (claim.isStated() && claim.getStatedDue() != null);
    }

    /** Only OPEN and SETTLED claims count toward what is claimed and due; a held person's OPEN ones do not. */
    private static boolean counts(Claim claim, Person person) {
        return (claim.getStatus() == ClaimStatus.OPEN && !person.isHeld())
                || claim.getStatus() == ClaimStatus.SETTLED;
    }
}
