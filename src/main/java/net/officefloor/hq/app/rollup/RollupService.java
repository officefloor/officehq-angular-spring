package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the sheet's
 * claimed-total and due-total. A change takes a snapshot before it and, once its own audit lines are
 * written, records a ROLLUP line per person whose figures moved and a TOTALS line when the totals did.
 */
@Service
public class RollupService {

    /** A person's figures, as text with two decimals; the due-total is "n/a" when they have none. */
    public record Figures(String name, String claimed, String dueTotal) {}

    /** Each person's figures by id, in the order they were added, and the sheet's totals. */
    public record Rollup(Map<Long, Figures> people, String claimedTotal, String dueTotal) {}

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
     * The sheet's figures now. Only open and settled claims count (a held person's open ones do not); a person's due-total is the sum of
     * their figures (those with one), n/a when the grade in force on the sheet's today has no rate in
     * force then and no counting claim of theirs has a figure.
     */
    @Transactional(readOnly = true)
    public Rollup snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElseGet(LocalDate::now);
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus().counts())
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> out = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal dueTotal = BigDecimal.ZERO.setScale(2);
        for (Person p : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> counted = byPerson.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> counts(p, c))
                    .toList();
            BigDecimal claimed = BigDecimal.ZERO.setScale(2);
            BigDecimal due = BigDecimal.ZERO.setScale(2);
            boolean anyFigure = false;
            for (Claim c : counted) {
                claimed = claimed.add(c.getAmount());
                BigDecimal figure = figure(sheetId, p, c);
                if (figure != null) due = due.add(figure);
                anyFigure |= figure != null;
            }
            boolean rated = rates.findInForce(sheetId, p.gradeOn(today), today).isPresent();
            claimedTotal = claimedTotal.add(claimed);
            if (rated || anyFigure) dueTotal = dueTotal.add(due);
            out.put(p.getId(), new Figures(p.getName(), text(claimed), rated || anyFigure ? text(due) : "n/a"));
        }
        return new Rollup(out, text(claimedTotal), text(dueTotal));
    }

    /**
     * Whether a claim counts in the live totals: open (pending ones among them) and settled claims do,
     * except the open claims of a person on hold.
     */
    public static boolean counts(Person person, Claim claim) {
        return claim.getStatus().counts() && !(person.isHeld() && claim.getStatus().isOpen());
    }

    /**
     * Record, once the change commits, a ROLLUP line for each person whose figures moved since
     * {@code before} (a person who did not exist then counts as moved), then a TOTALS line when the
     * sheet's totals moved.
     */
    @Transactional(readOnly = true)
    public void recordChanges(String sheetId, String user, Rollup before) {
        Rollup after = snapshot(sheetId);
        List<String> lines = new ArrayList<>();
        after.people().forEach((id, now) -> {
            Figures was = before.people().get(id);
            if (was == null || !was.claimed().equals(now.claimed()) || !was.dueTotal().equals(now.dueTotal())) {
                lines.add("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                        + " claimed=" + now.claimed() + " due-total=" + now.dueTotal());
            }
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())) {
            lines.add("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal());
        }
        lines.forEach(audit::recordOnCommit);
    }

    /**
     * The figure a claim shows: a settled claim's fixed due (any late charge frozen in), else its
     * bound figure (see {@link #boundFigure}) with, when it is overdue, its late charge on top; null
     * when none.
     */
    @Transactional(readOnly = true)
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getDue() != null) return claim.getDue();
        BigDecimal figure = boundFigure(sheetId, person, claim);
        if (figure == null || claim.isCorrection()) return figure;
        return sheets.findById(sheetId)
                .map(sheet -> figure.add(lateCharge(sheet, claim, figure, rounding(sheet, claim))))
                .orElse(figure);
    }

    /**
     * The figure a statement binds a live claim to, without any late charge: the figure its person's
     * last statement bound it to (a pending claim is not bound: its figure is live), else its amount times the rate in force on its date for the grade
     * in force on its date (rounded as {@link #rounding} says), raised to the sheet's floor (a
     * correction has no floor); null when none.
     */
    @Transactional(readOnly = true)
    public BigDecimal boundFigure(String sheetId, Person person, Claim claim) {
        if (claim.isStated() && claim.getStatus() != ClaimStatus.PENDING) return claim.getStatedDue();
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        BigDecimal floor = claim.isCorrection() ? null : sheet == null ? BigDecimal.ZERO : sheet.getFloor();
        RoundingMode rounding = sheet == null ? RoundingMode.HALF_EVEN : rounding(sheet, claim);
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(rate -> due(claim.getAmount(), rate, rounding))
                .map(due -> (floor == null ? due : due.max(floor)).setScale(2))
                .orElse(null);
    }

    /**
     * An overdue claim's late charge on a figure: the sheet's late percent of it for every full 30
     * days the claim is overdue (open, due by its date plus the terms, before the sheet's today),
     * rounded like due; zero when it is not overdue.
     */
    private static BigDecimal lateCharge(Sheet sheet, Claim claim, BigDecimal figure, RoundingMode rounding) {
        if (!claim.getStatus().isOpen()) return BigDecimal.ZERO;
        long daysOver = ChronoUnit.DAYS.between(claim.getDate().plusDays(sheet.getTerms()), sheet.getToday());
        if (daysOver <= 0) return BigDecimal.ZERO;
        BigDecimal pct = sheet.getLatePct().multiply(BigDecimal.valueOf(daysOver / 30));
        return figure.multiply(pct).movePointLeft(2).setScale(2, rounding);
    }

    /**
     * How a claim's live figures round: half up when it is dated before the sheet's policy date (its
     * today when none is set), else half to even. A correction rounds by the date of the claim it
     * corrects (its own when that is not found).
     */
    private RoundingMode rounding(Sheet sheet, Claim claim) {
        LocalDate dated = claim.getDate();
        if (claim.getCorrects() != null) {
            dated = claims.findBySheetIdOrderByIdDesc(sheet.getId()).stream()
                    .filter(c -> c.getPersonId().equals(claim.getPersonId())
                            && claim.getCorrects().equals(c.getNumber()) && !c.getId().equals(claim.getId()))
                    .map(Claim::getDate)
                    .findFirst()
                    .orElse(dated);
        }
        return dated.isBefore(sheet.policyDateInForce()) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The amount times the rate's percent, rounded to cents as given (as the screen works it out). */
    private static BigDecimal due(BigDecimal amount, Rate rate, RoundingMode rounding) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, rounding);
    }

    private static String text(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }
}
