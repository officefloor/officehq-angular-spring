package net.officefloor.hq.app.claim;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The sheet's rounding policy: a claim dated before the policy date rounds half up, one dated on
 * or after it half to even. It decides every rounding of the claim's live figures (the rate step
 * and the late charge); a correction rounds by the date of the claim it corrects.
 */
public record Rounding(LocalDate policyDate) {

    /** The rounding of a sheet as it is now; a sheet never loaded takes today as its policy date. */
    public static Rounding of(Optional<Sheet> sheet) {
        return new Rounding(sheet.map(Sheet::getPolicyDateInForce).orElseGet(LocalDate::now));
    }

    /** How a claim whose rounding date is {@code date} rounds. */
    public RoundingMode on(LocalDate date) {
        return date.isBefore(policyDate) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }
}
