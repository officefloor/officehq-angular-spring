package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the
 * sheet's claimed-total and due-total. A change takes a {@link #snapshot} before it alters anything
 * and calls {@link #recordChanges} after its own audit lines, which appends one ROLLUP line per
 * person whose figures moved (people in the order they were added; a new person counts as moved),
 * then a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /** A person's figures: claimed, and due-total (null for n/a). */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal) {
    }

    /** The sheet's figures: each person's by id, in the order they were added. */
    public record Snapshot(Map<Long, Figures> people) {

        public BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        public BigDecimal dueTotal() {
            return people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull).reduce(zero(),
                    BigDecimal::add);
        }
    }

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

    /** The sheet's figures as the store holds them now. */
    public Snapshot snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElseGet(LocalDate::now);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            boolean anyFixed = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !claim.getStatus().counts()) {
                    continue;
                }
                claimed = claimed.add(claim.getAmount());
                if (fixed(claim) != null) {
                    anyFixed = true;
                }
                BigDecimal figure = figure(sheetRates, person, claim);
                if (figure != null) {
                    due = due.add(figure);
                }
            }
            // as on screen: n/a when the grade has no rate today and no claim that counts keeps a figure
            boolean none = inForce(sheetRates, person.getGrade(), today).isEmpty() && !anyFixed;
            figures.put(person.getId(), new Figures(person.getName(), claimed, none ? null : due));
        }
        return new Snapshot(figures);
    }

    /** Record, once the change commits, the figures that moved since the snapshot taken before it. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        boolean moved = false;
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures now = entry.getValue();
            Figures was = before.people().get(entry.getKey());
            if (was != null && same(was.claimed(), now.claimed()) && same(was.dueTotal(), now.dueTotal())) {
                continue;
            }
            moved = true;
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + money(now.claimed())
                    + " due-total=" + money(now.dueTotal()));
        }
        if (moved && !(same(before.claimedTotal(), after.claimedTotal())
                && same(before.dueTotal(), after.dueTotal()))) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + money(after.claimedTotal()) + " due-total="
                    + money(after.dueTotal()));
        }
    }

    /** A claim's fixed figure: the one bound by the last statement, else the due fixed when settled. */
    private static BigDecimal fixed(Claim claim) {
        return claim.isStated() ? claim.getStatedDue() : claim.getSettledDue();
    }

    /** A claim's figure as the screen shows it: its fixed figure, else its live due; null when none. */
    private static BigDecimal figure(List<Rate> sheetRates, Person person, Claim claim) {
        if (claim.isStated()) {
            return claim.getStatedDue();
        }
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return inForce(sheetRates, person.getGrade(), claim.getDate()).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    /** The grade's rate in force on a date, as {@link RateRepository#findInForce} picks it. */
    private static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream().filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max((a, b) -> a.getFromDate() == null ? -1 : b.getFromDate() == null ? 1
                        : a.getFromDate().compareTo(b.getFromDate()));
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
