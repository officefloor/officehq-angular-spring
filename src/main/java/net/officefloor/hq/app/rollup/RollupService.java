package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures each person on a sheet shows — what they have claimed (their OPEN and SETTLED claims,
 * leaving out the OPEN ones while they are on hold)
 * and their due-total (the sum of what is due on those claims as each shows it, n/a when none has a figure and no rate of
 * their grade is in force on the sheet's today) — and the sheet's totals of them. A change that moves
 * any of them is followed in the audit by a ROLLUP line per person whose figures moved, then a TOTALS
 * line when the sheet's totals moved: take a {@link #snapshot} before the change, and
 * {@link #recordSince} it after the change has recorded its own lines.
 */
@Service
public class RollupService {

    /** A due-total shown when there is none. */
    public static final String NONE = "n/a";

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

    /** A person's figures, as shown. */
    public record Figures(String name, String claimed, String dueTotal) {
    }

    /** The sheet's figures at one moment: each person's, by id in the order they were added, and the totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal) {
    }

    /** The figures the sheet shows now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        BigDecimal dueTotal = zero();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            boolean any = false;
            for (Claim claim : all) {
                if (!claim.getPersonId().equals(person.getId()) || !countsLive(claim, person)) continue;
                claimed = claimed.add(claim.getAmount());
                BigDecimal figure = shownOf(claim, person);
                if (figure == null) continue;
                due = due.add(figure);
                any = true;
            }
            boolean rated = today != null && rates.findInForce(sheetId, person.getGrade(), today).isPresent();
            claimedTotal = claimedTotal.add(claimed);
            if (any || rated) dueTotal = dueTotal.add(due);
            figures.put(person.getId(), new Figures(person.getName(), text(claimed), any || rated ? text(due) : NONE));
        }
        return new Snapshot(figures, text(claimedTotal), text(dueTotal));
    }

    /** Record the ROLLUP and TOTALS lines for the figures that moved since {@code before}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSince(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            Figures then = before.people().get(id);
            if (then != null && then.claimed().equals(now.claimed()) && then.dueTotal().equals(now.dueTotal())) return;
            audit.change("ROLLUP", sheetId, user, "person=" + now.name(), "claimed=" + now.claimed(),
                    "due-total=" + now.dueTotal());
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())) {
            audit.change("TOTALS", sheetId, user, "claimed-total=" + after.claimedTotal(),
                    "due-total=" + after.dueTotal());
        }
    }

    /** Whether the claim counts in the live totals: OPEN or SETTLED, but not OPEN while its person
     * is on hold. */
    public static boolean countsLive(Claim claim, Person person) {
        return claim.getStatus().counts() && !(person.isHeld() && claim.getStatus() == ClaimStatus.OPEN);
    }

    /** The figure the claim shows and totals now: what was fixed when it was settled at a rate;
     * otherwise the figure a statement bound it to; otherwise what is due on it at its person's rate.
     * Null when it has no figure. */
    public BigDecimal figureOf(Claim claim, Person person) {
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isStated()) return claim.getStatedDue();
        return dueOf(claim, person);
    }

    /** What is due on the claim as it shows and totals now: its figure, plus its late charge; null when
     * it has no figure. */
    public BigDecimal shownOf(Claim claim, Person person) {
        BigDecimal figure = figureOf(claim, person);
        return figure == null ? null : figure.add(lateChargeOf(claim, figure));
    }

    /** The late charge on an OPEN claim whose figure is {@code figure}: the sheet's late percent of that
     * figure for every full 30 days the claim is overdue (past its due-by, its date plus the sheet's
     * terms, on the sheet's today), rounded half to even to two decimals; 0.00 on a claim not OPEN or
     * not overdue. A statement never binds it: it follows the sheet's today until the claim is settled. */
    public BigDecimal lateChargeOf(Claim claim, BigDecimal figure) {
        if (claim.getStatus() != ClaimStatus.OPEN) return zero();
        Sheet sheet = sheets.findById(claim.getSheetId()).orElse(null);
        if (sheet == null) return zero();
        LocalDate dueBy = claim.getDate().plusDays(sheet.getTerms());
        long periods = Math.max(0, ChronoUnit.DAYS.between(dueBy, sheet.getToday())) / 30;
        return figure.multiply(sheet.getLatePct()).multiply(BigDecimal.valueOf(periods)).movePointLeft(2)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /** What is due on the claim at its person's rate in force on the claim's date: amount times pct,
     * divided by 100, rounded half to even to two decimals, and at least the sheet's floor; null when no
     * rate of their grade applies then. */
    public BigDecimal dueOf(Claim claim, Person person) {
        BigDecimal floor = sheets.findById(claim.getSheetId()).map(Sheet::getFloor).orElse(zero());
        return rates.findInForce(claim.getSheetId(), person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .map(due -> due.max(floor))
                .orElse(null);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String text(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
