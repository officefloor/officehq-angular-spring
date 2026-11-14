package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The sheet's late charge: an OPEN claim overdue (its due-by, its date plus the terms, before the
 * sheet's today) gains the late percent of its figure for every full 30 days it is overdue,
 * rounded half to even, on top. It is never bound by a statement; settling freezes it in.
 */
public record LateCharge(LocalDate today, int terms, BigDecimal pct) {

    /** The days in each step of the charge. */
    private static final int STEP_DAYS = 30;

    /** The late charge of a sheet as it is now; a sheet never loaded charges nothing. */
    public static LateCharge of(Optional<Sheet> sheet) {
        return new LateCharge(sheet.map(Sheet::getToday).orElseGet(LocalDate::now),
                sheet.map(Sheet::getTerms).orElse(Sheet.DEFAULT_TERMS),
                sheet.map(Sheet::getLatePct).orElse(Sheet.DEFAULT_LATE_PCT));
    }

    /** The claim's figure (null: n/a, which stays n/a) with its late charge added. */
    public BigDecimal addTo(Claim claim, BigDecimal figure) {
        if (figure == null || claim.getStatus() != ClaimStatus.OPEN) {
            return figure;
        }
        long overdue = ChronoUnit.DAYS.between(claim.getDate().plusDays(terms), today);
        if (overdue <= 0) {
            return figure;
        }
        BigDecimal steps = BigDecimal.valueOf(overdue / STEP_DAYS);
        return figure.add(figure.multiply(pct).multiply(steps).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN));
    }
}
