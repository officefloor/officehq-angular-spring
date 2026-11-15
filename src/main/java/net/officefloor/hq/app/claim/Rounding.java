package net.officefloor.hq.app.claim;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;
import net.officefloor.hq.app.sheet.Sheet;

/**
 * The sheet's rounding policy: a claim dated when a rounding mode is in force rounds by it; with
 * none in force then, a claim dated before the policy date rounds half up, one dated on or after it
 * half to even. It decides every rounding of the claim's live figures (the rate step
 * and the late charge); a correction rounds by the date of the claim it corrects.
 */
public record Rounding(LocalDate policyDate, Function<LocalDate, String> modeOn) {

    /** The key of the rounding mode that rounds half up. */
    public static final String HALF_UP = "half-up";
    /** The key of the rounding mode that rounds half to even. */
    public static final String HALF_EVEN = "half-even";

    /** The rounding of a sheet as it is now; a sheet never loaded takes today as its policy date. */
    public static Rounding of(Optional<Sheet> sheet) {
        return new Rounding(sheet.map(Sheet::getPolicyDateInForce).orElseGet(LocalDate::now),
                on -> sheet.map(s -> s.roundingOn(on)).orElse(null));
    }

    /** How a claim whose rounding date is {@code date} rounds. */
    public RoundingMode on(LocalDate date) {
        String mode = modeOn.apply(date);
        if (HALF_UP.equals(mode)) {
            return RoundingMode.HALF_UP;
        }
        if (HALF_EVEN.equals(mode)) {
            return RoundingMode.HALF_EVEN;
        }
        return date.isBefore(policyDate) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }
}
