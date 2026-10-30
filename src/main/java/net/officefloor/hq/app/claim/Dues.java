package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.rate.Grades;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;

/** The due figures of claims and the due-total of a person's claims. */
public final class Dues {

    private Dues() {
    }

    /**
     * A claim's due: once settled, the due fixed then (its late charge frozen in); otherwise its
     * {@link #base} figure plus, while overdue, its {@link RateTable#lateCharge late charge}. Null when there
     * is none.
     */
    public static BigDecimal figure(Claim claim, Grades grades, RateTable rates) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal base = base(claim, grades, rates);
        if (base == null || claim.isCorrection() || !overdue(claim, rates)) {
            return base;
        }
        return base.add(rates.lateCharge(base, rates.dueBy(claim.getDate())));
    }

    /**
     * A claim's figure before any late charge: while bound by a statement, the figure bound then;
     * otherwise the amount at the rate in force on its date for its person's grade in force then, but at
     * least the sheet's floor; a correction has no floor and no late charge. Null when there is none. A
     * statement binds this figure, never the late charge.
     */
    public static BigDecimal base(Claim claim, Grades grades, RateTable rates) {
        if (claim.isStated()) {
            return claim.getStatedDue();
        }
        String grade = grades.on(claim.getDate());
        return claim.isCorrection() ? RateService.due(claim.getAmount(), rates.on(grade, claim.getDate()))
                : rates.due(claim.getAmount(), grade, claim.getDate());
    }

    /** Whether the claim is overdue: OPEN, and due by a date before the sheet's today. */
    public static boolean overdue(Claim claim, RateTable rates) {
        return claim.getStatus() == ClaimStatus.OPEN && rates.dueBy(claim.getDate()).isBefore(rates.today());
    }

    /** The overdue claims that count, see {@link #overdue} and {@link #counts}. */
    public static List<Claim> overdue(List<Claim> claims, RateTable rates, boolean held) {
        return claims.stream().filter(c -> counts(c, held) && overdue(c, rates)).toList();
    }

    /** The sum of the due figures of the claims given (0.00 with none); a claim with none is skipped. */
    public static BigDecimal sum(Grades grades, List<Claim> claims, RateTable rates) {
        return claims.stream().map(c -> figure(c, grades, rates)).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates) {
        return total(grades, claims, rates, false);
    }

    /**
     * The sum of the due figures of the claims that count (0.00 with none), see {@link #counts}. Null (n/a)
     * when the grade in force on the sheet's today has no rate on it, unless a claim that counts has a
     * figure not made by that grade (kept, or at the rate for another grade in force on its date): then it
     * is the sum of the figures there are.
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates, boolean held) {
        List<Claim> counting = claims.stream().filter(c -> counts(c, held)).toList();
        String current = grades.on(rates.today());
        boolean shown = rates.current(current) != null || counting.stream().anyMatch(c -> kept(c)
                || (!current.equals(grades.on(c.getDate())) && figure(c, grades, rates) != null));
        return shown ? counting.stream().map(c -> figure(c, grades, rates))
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
    }
}
