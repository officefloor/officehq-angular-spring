package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     * A claim's due: once settled, the due fixed then (its late charge frozen in); a correction, its
     * {@link #base} figure (no floor, no late charge); otherwise the greater of the floor in force on its date
     * and its {@link #base} figure plus, while overdue, its {@link RateTable#lateCharge late charge} on that
     * figure. Null when there is none.
     */
    public static BigDecimal figure(Claim claim, Grades grades, RateTable rates) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal base = base(claim, grades, rates);
        if (base == null || claim.isCorrection()) {
            return base;
        }
        BigDecimal due = overdue(claim, rates)
                ? base.add(rates.lateCharge(base, claim.getDueBy(), claim.getRoundsBy()))
                : base;
        return rates.floored(due, claim.getDate());
    }

    /**
     * A claim's figure before any late charge or floor: while bound by a statement (and not PENDING, which is
     * never bound), the figure bound then; otherwise its {@link #rateFigure rate figure}. Null when there is
     * none. A statement binds this figure, never the late charge or the floor.
     */
    public static BigDecimal base(Claim claim, Grades grades, RateTable rates) {
        if (bound(claim)) {
            return claim.getStatedDue();
        }
        return rateFigure(claim, grades, rates);
    }

    /**
     * The amount at the rate in force on the claim's date for its person's grade in force then, rounded by
     * its date (a correction by the date its rounding goes by), see {@link RateTable#rounding}; no floor. Null when no rate is.
     */
    private static BigDecimal rateFigure(Claim claim, Grades grades, RateTable rates) {
        String grade = grades.on(claim.getDate());
        return claim.isCorrection() ? RateService.due(claim.getAmount(), rates.on(grade, claim.getDate()),
                rates.rounding(claim.getRoundsBy()))
                : rates.figure(claim.getAmount(), grade, claim.getDate());
    }

    /**
     * The figure the claim would have today as an OPEN claim bound by no statement: its rate figure plus the
     * late charge it has today on that figure when its due-by has passed, but at least the floor in force on
     * its date; a correction has neither floor nor late charge. Null when there is none. Re-settling a claim
     * fixes its due at this figure.
     */
    public static BigDecimal asOpen(Claim claim, Grades grades, RateTable rates) {
        BigDecimal base = rateFigure(claim, grades, rates);
        if (base == null || claim.isCorrection()) {
            return base;
        }
        BigDecimal due = claim.getDueBy().isBefore(rates.today())
                ? base.add(rates.lateCharge(base, claim.getDueBy(), claim.getRoundsBy()))
                : base;
        return rates.floored(due, claim.getDate());
    }

    /** Whether a statement binds the claim's figure: it is stated and not PENDING. */
    public static boolean bound(Claim claim) {
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING;
    }

    /**
     * Whether the claim is overdue: OPEN or PENDING, not {@link #deferred deferred}, and due by a date before
     * the sheet's today (its due-by, fixed when it was added).
     */
    public static boolean overdue(Claim claim, RateTable rates) {
        return claim.getStatus().unpaid() && !deferred(claim, rates.today()) && claim.getDueBy().isBefore(rates.today());
    }

    /** Whether the claim is deferred on the given today: OPEN and deferred until a date after it. */
    public static boolean deferred(Claim claim, LocalDate today) {
        return claim.getStatus() == ClaimStatus.OPEN && claim.getDeferredUntil() != null
                && today.isBefore(claim.getDeferredUntil());
    }

    /** The overdue claims that count, see {@link #overdue} and {@link #counts}. */
    public static List<Claim> overdue(List<Claim> claims, RateTable rates, Holds holds) {
        return claims.stream().filter(c -> counts(c, holds, rates.today()) && overdue(c, rates)).toList();
    }

    /** The sum of the due figures of the claims given (0.00 with none); a claim with none is skipped. */
    public static BigDecimal sum(Grades grades, List<Claim> claims, RateTable rates) {
        return claims.stream().map(c -> figure(c, grades, rates)).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether the claim's figure is kept whatever the rates: fixed by settling or bound by a statement. */
    private static boolean kept(Claim claim) {
        return claim.getSettledDue() != null || (bound(claim) && claim.getStatedDue() != null);
    }

    /**
     * Whether the claim counts in the live totals on the sheet's today: OPEN, PENDING and SETTLED claims do,
     * except that an OPEN claim dated within one of its person's holds counts in none, unless a statement
     * binds it, and a claim {@link #deferred deferred} on that today counts in none.
     */
    public static boolean counts(Claim claim, Holds holds, LocalDate today) {
        return claim.getStatus().counts() && !deferred(claim, today) && !(claim.getStatus() == ClaimStatus.OPEN
                && !bound(claim) && holds.on(claim.getDate()));
    }

    /**
     * The status the claim had at the end of the given day, as its status log records it (each line on the
     * sheet's today then); null when it was added after that day.
     */
    public static ClaimStatus statusAsOf(Claim claim, LocalDate day) {
        ClaimStatus status = null;
        for (ClaimLogLine line : claim.getLog()) {
            if (!line.getOn().isAfter(day)) {
                status = line.getStatus();
            }
        }
        return status;
    }

    /**
     * Whether the claim counts in a period ending on {@code end}: its status at the end of that day (see
     * {@link #statusAsOf}) is OPEN, PENDING or SETTLED, and it is neither left out by a hold nor deferred on the
     * sheet's today, as in {@link #counts}.
     */
    public static boolean countsAsOf(Claim claim, Holds holds, LocalDate today, LocalDate end) {
        ClaimStatus then = statusAsOf(claim, end);
        return then != null && then.counts() && !deferred(claim, today) && !(claim.getStatus() == ClaimStatus.OPEN
                && !bound(claim) && holds.on(claim.getDate()));
    }

    /**
     * A person's due-total (see {@link #total}); a person not on hold.
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates) {
        return total(grades, claims, rates, Holds.NONE);
    }

    /**
     * A person's due-total: their {@link #uncapped} sum held to the sheet's cap, see {@link RateTable#capped}.
     * Null (n/a) when the sum is.
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return rates.capped(uncapped(grades, claims, rates, holds));
    }

    /** What a person's {@link #uncapped} sum is above the sheet's cap: 0.00 when it is not, or is n/a. */
    public static BigDecimal excess(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return rates.excess(uncapped(grades, claims, rates, holds));
    }

    /**
     * The sum of the due figures of the claims that count (0.00 with none), see {@link #counts}. Null (n/a)
     * when the grade in force on the sheet's today has no rate on it, unless a claim that counts has a
     * figure not made by that grade (kept, or at the rate for another grade in force on its date): then it
     * is the sum of the figures there are; so too when that grade's rate has ended by the sheet's today and
     * a claim that counts has a figure.
     */
    public static BigDecimal uncapped(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        List<Claim> counting = claims.stream().filter(c -> counts(c, holds, rates.today())).toList();
        String current = grades.on(rates.today());
        boolean ended = rates.ended(current, rates.today());
        boolean shown = rates.current(current) != null || counting.stream().anyMatch(c -> kept(c)
                || ((ended || !current.equals(grades.on(c.getDate()))) && figure(c, grades, rates) != null));
        return shown ? counting.stream().map(c -> figure(c, grades, rates))
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
    }
}
