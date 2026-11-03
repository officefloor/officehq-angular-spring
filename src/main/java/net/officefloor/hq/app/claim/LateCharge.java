package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The late charge on an overdue claim: the sheet's late percent of the claim's figure for every
 * full 30 days it is overdue (OPEN, and past its date plus the sheet's terms on the sheet's today),
 * rounded to the cent as the claim rounds (see {@link Rounding}). A correction has none. Never bound by a statement; settling a claim fixes it into its due.
 */
public final class LateCharge {

    /** The days overdue that each add one late percent. */
    private static final int PERIOD_DAYS = 30;

    private LateCharge() {
    }

    /** The figure with its late charge added on top; null when the figure is null. */
    public static BigDecimal withCharge(Sheet sheet, Claim claim, BigDecimal figure) {
        return figure == null ? null : figure.add(charge(sheet, claim, figure));
    }

    /** The late charge on a claim's figure on the sheet's today; zero when it is not overdue. */
    public static BigDecimal charge(Sheet sheet, Claim claim, BigDecimal figure) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (figure == null || claim.getStatus() != ClaimStatus.OPEN || claim.isCorrection()) {
            return zero;
        }
        LocalDate dueBy = claim.getDate().plusDays(sheet.getTerms());
        long periods = ChronoUnit.DAYS.between(dueBy, sheet.getToday()) / PERIOD_DAYS;
        if (periods <= 0) {
            return zero;
        }
        return figure.multiply(sheet.getLatePct()).multiply(BigDecimal.valueOf(periods))
                .divide(BigDecimal.valueOf(100), 2, Rounding.mode(sheet, claim.getDate()));
    }
}
