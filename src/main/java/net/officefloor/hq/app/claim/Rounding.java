package net.officefloor.hq.app.claim;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a claim's live figures (the rate step and the late charge) round to cents: half up when the
 * claim is dated before the sheet's policy date (none: the sheet's today), else half to even. A
 * correction rounds by the date of the claim it corrects, not its own.
 */
public final class Rounding {

    private Rounding() {}

    /** The date a claim rounds by: a correction's corrected claim's date (from {@code datesByNumber}), else its own. */
    public static LocalDate basisOf(Claim claim, Map<String, LocalDate> datesByNumber) {
        if (claim.getCorrects() != null) {
            LocalDate corrected = datesByNumber.get(claim.getCorrects());
            if (corrected != null) return corrected;
        }
        return claim.getDate();
    }

    /** The rounding of a claim rounding by the given date. */
    public static RoundingMode of(LocalDate basis, LocalDate policyDate, LocalDate today) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return policy != null && basis.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The dates of the numbered claims, by number. */
    public static Map<String, LocalDate> datesByNumber(List<Claim> claims) {
        Map<String, LocalDate> dates = new HashMap<>();
        for (Claim c : claims) {
            if (c.getNumber() != null) dates.put(c.getNumber(), c.getDate());
        }
        return dates;
    }
}
