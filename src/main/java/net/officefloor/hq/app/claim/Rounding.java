package net.officefloor.hq.app.claim;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * How a claim's live figures are rounded to the cent: half up when the claim is dated before the
 * sheet's policy date (its today when it has none), half to even when dated on or after it. A
 * correction rounds by the date of the claim it corrects, not its own.
 */
public final class Rounding {

    private Rounding() {
    }

    /** The rounding for a claim dated on the given date. */
    public static RoundingMode mode(Sheet sheet, LocalDate date) {
        LocalDate policy = sheet.getPolicyDate() != null ? sheet.getPolicyDate() : sheet.getToday();
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /**
     * The date a claim rounds by: its own, or for a correction the date of the claim it corrects,
     * found by number among the sheet's claims (its own when that claim is not among them).
     */
    public static LocalDate dateOf(Claim claim, Collection<Claim> sheetClaims) {
        if (!claim.isCorrection() || claim.getCorrects() == null) {
            return claim.getDate();
        }
        return sheetClaims.stream().filter(c -> claim.getCorrects().equals(c.getNumber())).findFirst()
                .map(Claim::getDate).orElse(claim.getDate());
    }
}
