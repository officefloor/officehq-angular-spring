package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;

/** What is due on a claim, read from the rates its person's grade has. */
@Component
public class Dues {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final ClaimRepository claims;

    public Dues(RateRepository rates, SheetRepository sheets, ClaimRepository claims) {
        this.rates = rates;
        this.sheets = sheets;
        this.claims = claims;
    }

    /** How many levels of parent sheets a grade with no rate in force falls back to. */
    public static final int PARENT_LEVELS = 3;

    /**
     * What a claim's figures are read against: the sheet (its today and its dated settings; null when
     * not found), the sheet's rates, its ancestors' rates (nearest first, up to {@link #PARENT_LEVELS}),
     * and how the claim a correction names is found.
     */
    public record Basis(Sheet sheet, List<Rate> rates, List<List<Rate>> inherited,
            Function<Claim, Optional<Claim>> corrected) {

        /**
         * The rate in force for a grade on a date (see {@link RateRepository#findInForce}); when the sheet
         * has none, its parent's, then its parent's parent's, and so on.
         */
        public Optional<Rate> rateInForce(String grade, LocalDate date) {
            Optional<Rate> rate = RateRepository.inForce(rates, grade, date);
            for (int level = 0; rate.isEmpty() && level < inherited.size(); level++) {
                rate = RateRepository.inForce(inherited.get(level), grade, date);
            }
            return rate;
        }

        /**
         * The rate in force for a grade on a claim's date as the claim sees the rates: a forward-only rate
         * saved after the claim was added is passed over (see {@link RateRepository#inForceFor}); when the
         * sheet has none, its parent's, then its parent's parent's, and so on.
         */
        public Optional<Rate> rateInForceFor(Claim claim, String grade) {
            Optional<Rate> rate = RateRepository.inForceFor(rates, grade, claim.getDate(), claim.getAdded());
            for (int level = 0; rate.isEmpty() && level < inherited.size(); level++) {
                rate = RateRepository.inForceFor(inherited.get(level), grade, claim.getDate(), claim.getAdded());
            }
            return rate;
        }

        LocalDate today() {
            return sheet == null ? null : sheet.getToday();
        }
    }

    /** The sheet as the store holds it now: what every live figure is read against. */
    public Basis live(String sheetId) {
        return new Basis(sheets.findById(sheetId).orElse(null), rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId),
                inherited(sheetId), claim -> claims.findBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects()));
    }

    /** The rates of the sheet's ancestors as the store holds them now, nearest first, up to {@link #PARENT_LEVELS}. */
    public List<List<Rate>> inherited(String sheetId) {
        return sheets.ancestors(sheetId).stream().limit(PARENT_LEVELS)
                .map(rates::findBySheetIdOrderByGradeAscFromDateAscIdAsc).toList();
    }

    /**
     * How a claim's live figures round: by the sheet's policy date against the claim's date, or for a
     * correction the date of the claim it corrects (its own when that claim is not found).
     */
    private RoundingMode rounding(Claim claim, Basis basis) {
        LocalDate date = claim.getDate();
        if (claim.getCorrects() != null) {
            date = basis.corrected().apply(claim).map(Claim::getDate).orElse(date);
        }
        Sheet sheet = basis.sheet();
        return sheet == null ? RoundingMode.HALF_EVEN : sheet.roundingOn(date);
    }

    /**
     * The due figure a claim shows, before its late charge and floor: the one fixed when it was settled,
     * else the rate figure its person's last statement bound it to, else its amount at the rate the grade has in force on the claim's date; null (n/a) when there is none.
     */
    public BigDecimal figure(Claim claim, String grade) {
        return figure(claim, grade, live(claim.getSheetId()));
    }

    /** The due figure a claim shows (see {@link #figure(Claim, String)}), read against a basis. */
    public BigDecimal figure(Claim claim, String grade, Basis basis) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : live(claim, grade, basis);
    }

    /**
     * The due a claim shows: the greater of the floor in force on its date and its figure (see
     * {@link #figure}) with the late charge on top while it is overdue; a settled claim's figure is
     * frozen as it was settled. Null (n/a) when it has no figure.
     */
    public BigDecimal due(Claim claim, String grade) {
        return due(claim, grade, live(claim.getSheetId()));
    }

    /** The due a claim shows (see {@link #due(Claim, String)}), read against a basis. */
    public BigDecimal due(Claim claim, String grade, Basis basis) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return floored(claim, withLate(claim, grade, figure(claim, grade, basis), basis), basis);
    }

    /**
     * The due a claim is settled at: the figure a statement bound it to, else its live figure, with
     * the late charge it has on the sheet's today on top; null when it has no figure.
     */
    public BigDecimal settling(Claim claim, String grade) {
        Basis basis = live(claim.getSheetId());
        return floored(claim, withLate(claim, grade, claim.isStated() ? claim.getStatedDue() : live(claim, grade, basis), basis), basis);
    }

    /**
     * The due a PENDING claim is approved at: the figure a statement bound it to, else its live figure,
     * with the late charge it has on the sheet's today on top; null when it has no figure.
     */
    public BigDecimal approving(Claim claim, String grade) {
        return settling(claim, grade);
    }

    /**
     * The due a SETTLED claim is re-settled at: what it would show today as an OPEN claim bound by no
     * statement, its live figure with the late charge it would have on the sheet's today on top; null
     * when it has no figure.
     */
    public BigDecimal resettling(Claim claim, String grade) {
        Basis basis = live(claim.getSheetId());
        BigDecimal figure = live(claim, grade, basis);
        return figure == null || claim.isCorrection() ? figure : floored(claim, lateOn(claim, grade, figure, basis), basis);
    }

    /**
     * A figure with the claim's late charge on top while an OPEN or PENDING claim is past its due-by
     * (fixed when it was added) on the sheet's today (see {@link #lateOn}), rounded by the claim's date
     * against the policy date. The charge is never bound: it always follows the sheet's today.
     * A correction has no late charge, nor has a claim deferred on the sheet's today (it is not overdue).
     */
    private BigDecimal withLate(Claim claim, String grade, BigDecimal figure, Basis basis) {
        if (figure == null || claim.isCorrection() || !claim.getStatus().isOpen() || claim.getSettledDue() != null) {
            return figure;
        }
        if (basis.sheet() != null && claim.isDeferredOn(basis.today())) {
            return figure;
        }
        return lateOn(claim, grade, figure, basis);
    }

    /**
     * A figure with the late charge an OPEN claim would have on the sheet's today on top: for every full
     * 30 days past its due-by plus its grade's grace (see {@link Sheet#lateFrom}), the late percent in
     * force on that boundary date (that date plus 30, 60, 90 ... days) of the figure plus the charges of
     * the boundaries before it (the charge compounds), rounded like the claim, the charges added up.
     */
    private BigDecimal lateOn(Claim claim, String grade, BigDecimal figure, Basis basis) {
        Sheet sheet = basis.sheet();
        if (sheet == null) {
            return figure;
        }
        LocalDate dueBy = sheet.lateFrom(claim.getDueBy(), grade, claim.getDate());
        long periods = ChronoUnit.DAYS.between(dueBy, sheet.getToday()) / 30;
        RoundingMode rounding = rounding(claim, basis);
        BigDecimal late = BigDecimal.ZERO.setScale(2);
        for (long period = 1; period <= periods; period++) {
            BigDecimal latePct = sheet.latePctOn(dueBy.plusDays(30 * period));
            if (latePct != null && latePct.signum() != 0) {
                late = late.add(figure.add(late).multiply(latePct).movePointLeft(2).setScale(2, rounding));
            }
        }
        return figure.add(late);
    }

    /**
     * A due raised to the floor in force on the claim's date when below it, applied last (after the
     * late charge); a correction has no floor, and null (n/a) stays null.
     */
    private BigDecimal floored(Claim claim, BigDecimal due, Basis basis) {
        if (due == null || claim.isCorrection()) {
            return due;
        }
        Sheet sheet = basis.sheet();
        BigDecimal floor = sheet == null ? null : sheet.floorOn(claim.getDate());
        return floor != null && due.compareTo(floor) < 0 ? floor : due;
    }

    /**
     * A claim's rate figure: its amount at the rate the grade has in force on the claim's date as the
     * claim sees the rates (a forward-only rate saved after it was added passed over), rounded
     * by the claim's date against the policy date, with no floor; null when it has no rate.
     */
    public BigDecimal live(Claim claim, String grade) {
        return live(claim, grade, live(claim.getSheetId()));
    }

    private BigDecimal live(Claim claim, String grade, Basis basis) {
        RoundingMode rounding = rounding(claim, basis);
        return basis.rateInForceFor(claim, grade)
                .map(rate -> due(claim.getAmount(), rate, rounding))
                .orElse(null);
    }

    /** What is due on an amount at a rate: the amount times the percent over 100, rounded as given. */
    private static BigDecimal due(BigDecimal amount, Rate rate, RoundingMode rounding) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, rounding);
    }
}
