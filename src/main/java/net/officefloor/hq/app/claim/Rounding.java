package net.officefloor.hq.app.claim;

import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * How a claim's live figures round (the rate step and the late charge): half up when the claim is dated
 * before the sheet's policy date, half to even when on or after it. A correction rounds by the date of
 * the claim it corrects, not its own. The policy date is the sheet's today when it is blank.
 */
public final class Rounding {

    private Rounding() {
    }

    /** The policy date in force: the one the sheet set, else its today (null when neither is known). */
    public static LocalDate policyDate(LocalDate set, LocalDate today) {
        return set != null ? set : today;
    }

    /** The date that decides how the claim rounds: the date of the claim it corrects (when that is
     *  known), else its own. */
    public static LocalDate dateOf(Claim claim, Claim corrected) {
        return claim.isCorrection() && corrected != null ? corrected.getDate() : claim.getDate();
    }

    /** How a claim whose rounding is decided by the date rounds under the policy date. */
    public static RoundingMode of(LocalDate roundsBy, LocalDate policyDate) {
        return policyDate != null && roundsBy.isBefore(policyDate) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }
}
