package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.rate.RateTable;

/** The due figures of claims and the due-total of a person's claims. */
public final class Dues {

    private Dues() {
    }

    /**
     * A claim's due figure: once settled, the due fixed then; while bound by a statement, the figure
     * bound then; otherwise the amount at the rate for its person's grade in force on its date, but at
     * least the sheet's floor. Null when there is none.
     */
    public static BigDecimal figure(Claim claim, String grade, RateTable rates) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue()
                : rates.due(claim.getAmount(), grade, claim.getDate());
    }

    /** Whether the claim's figure is kept whatever the rates: fixed by settling or bound by a statement. */
    private static boolean kept(Claim claim) {
        return claim.getSettledDue() != null || (claim.isStated() && claim.getStatedDue() != null);
    }

    /**
     * Whether the claim counts in the live totals: OPEN and SETTLED claims do, except that the OPEN
     * claims of a person on hold count in none.
     */
    public static boolean counts(Claim claim, boolean held) {
        return claim.getStatus().counts() && !(held && claim.getStatus() == ClaimStatus.OPEN);
    }

    /**
     * The sum of the due figures of the claims that count (0.00 with none); a person not on hold.
     */
    public static BigDecimal total(String grade, List<Claim> claims, RateTable rates) {
        return total(grade, claims, rates, false);
    }

    /**
     * The sum of the due figures of the claims that count (0.00 with none), see {@link #counts}. Null (n/a) when the grade has
     * no rate on the sheet's today, unless a claim that counts keeps a figure: then it is the sum of the
     * figures there are.
     */
    public static BigDecimal total(String grade, List<Claim> claims, RateTable rates, boolean held) {
        List<Claim> counting = claims.stream().filter(c -> counts(c, held)).toList();
        boolean shown = rates.current(grade) != null || counting.stream().anyMatch(Dues::kept);
        return shown ? counting.stream().map(c -> figure(c, grade, rates))
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
    }
}
