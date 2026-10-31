package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.officefloor.hq.app.rate.Grades;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;

/** The due figures of claims and the due-total of a person's claims. */
public final class Dues {

    private Dues() {
    }

    /**
     * A claim's due: once settled, the due fixed then (its late charge frozen in); while bound by a statement
     * to its figure as shown, that figure (its late charge bound in); a correction, its
     * {@link #base} figure (no floor, no late charge); otherwise the greater of the floor in force on its date
     * and its {@link #base} figure (the floored figure) plus, while overdue and not waived, its
     * {@link RateTable#lateCharge late charge} on that floored figure. Null when there is none.
     */
    public static BigDecimal figure(Claim claim, Grades grades, RateTable rates) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        if (bound(claim) && claim.isStatedAsShown()) {
            return claim.getStatedDue();
        }
        BigDecimal base = base(claim, grades, rates);
        if (base == null || claim.isCorrection()) {
            return base;
        }
        BigDecimal floored = rates.floored(base, claim.getDate());
        return overdue(claim, grades, rates) && !claim.isWaived()
                ? floored.add(rates.lateCharge(floored, graceBy(claim, grades, rates), claim.getRoundsBy()))
                : floored;
    }

    /**
     * A claim's figure before any late charge or floor: while bound by a statement from before statements
     * bound the figure as shown, the figure bound then; otherwise its {@link #rateFigure rate figure}. Null
     * when there is none.
     */
    public static BigDecimal base(Claim claim, Grades grades, RateTable rates) {
        if (bound(claim) && !claim.isStatedAsShown()) {
            return claim.getStatedDue();
        }
        return rateFigure(claim, grades, rates);
    }

    /**
     * The amount at the rate in force on the claim's date for its person's grade in force then (a forward-only
     * rate only once the claim was added after it was saved), rounded by
     * its date (a correction by the date its rounding goes by), see {@link RateTable#rounding}; no floor. Null when no rate is.
     */
    private static BigDecimal rateFigure(Claim claim, Grades grades, RateTable rates) {
        String grade = grades.on(claim.getDate());
        return claim.isCorrection() ? RateService.due(claim.getAmount(), rates.on(grade, claim.getDate(), claim.getId()),
                rates.rounding(claim.getRoundsBy()))
                : rates.figure(claim.getAmount(), grade, claim.getDate(), claim.getId());
    }

    /**
     * The figure the claim would have today as an OPEN claim bound by no statement: its rate figure held up to
     * the floor in force on its date, plus the late charge it has today on that floored figure (none once waived)
     * when its due-by plus its grace has passed; a correction has neither floor nor late charge. Null when there is none. Re-settling a claim
     * fixes its due at this figure.
     */
    public static BigDecimal asOpen(Claim claim, Grades grades, RateTable rates) {
        BigDecimal base = rateFigure(claim, grades, rates);
        if (base == null || claim.isCorrection()) {
            return base;
        }
        LocalDate graceBy = graceBy(claim, grades, rates);
        BigDecimal floored = rates.floored(base, claim.getDate());
        return graceBy.isBefore(rates.today()) && !claim.isWaived()
                ? floored.add(rates.lateCharge(floored, graceBy, claim.getRoundsBy()))
                : floored;
    }

    /** Whether a statement binds the claim's figure: it is stated (whatever its status, PENDING included). */
    public static boolean bound(Claim claim) {
        return claim.isStated();
    }

    /**
     * Whether the claim is late in review: REVIEW, with the sheet's review SLA set, and its review day (the
     * sheet's today when it went to REVIEW) plus the SLA days before the sheet's today.
     */
    public static boolean reviewLate(Claim claim, RateTable rates) {
        Integer sla = rates.settings().reviewSla();
        return claim.getStatus() == ClaimStatus.REVIEW && sla != null && claim.getReviewOn() != null
                && claim.getReviewOn().plusDays(sla).isBefore(rates.today());
    }

    /**
     * Whether the claim is late pending: PENDING, with the sheet's pending SLA set, and its pending day (the
     * sheet's today when it last went to PENDING) plus the SLA days before the sheet's today.
     */
    public static boolean pendingLate(Claim claim, RateTable rates) {
        Integer sla = rates.settings().pendingSla();
        return claim.getStatus() == ClaimStatus.PENDING && sla != null && claim.getPendingOn() != null
                && claim.getPendingOn().plusDays(sla).isBefore(rates.today());
    }

    /**
     * Whether the claim is overdue: OPEN or PENDING, not {@link #deferred deferred}, and due by a date before
     * the sheet's today (its due-by, fixed when it was added, plus its {@link #graceBy grace}).
     */
    public static boolean overdue(Claim claim, Grades grades, RateTable rates) {
        return claim.getStatus().unpaid() && !deferred(claim, rates.today())
                && graceBy(claim, grades, rates).isBefore(rates.today());
    }

    /**
     * The date a claim's overdue and late charge count from: its due-by plus the grace days in force on its
     * date for its person's grade in force then (none: its due-by). The due-by shown is unchanged.
     */
    public static LocalDate graceBy(Claim claim, Grades grades, RateTable rates) {
        return claim.getDueBy().plusDays(rates.settings().grace(grades.on(claim.getDate()), claim.getDate()));
    }

    /**
     * Whether the claim is deferred on the given today: deferred until a date after it and not
     * {@link #bound bound} by a statement, OPEN or (settled while a statement bound it) SETTLED. A deferral
     * takes effect only once the binding ends: while bound the claim stays in the totals and can be settled.
     */
    public static boolean deferred(Claim claim, LocalDate today) {
        return deferralPending(claim, today)
                && (claim.getStatus() == ClaimStatus.OPEN || claim.getStatus() == ClaimStatus.SETTLED);
    }

    /** Whether the claim is deferred until a date after the given today and no statement binds it, whatever its status. */
    public static boolean deferralPending(Claim claim, LocalDate today) {
        return !bound(claim) && claim.getDeferredUntil() != null && today.isBefore(claim.getDeferredUntil());
    }

    /** The overdue claims that count, see {@link #overdue} and {@link #counts}. */
    public static List<Claim> overdue(List<Claim> claims, Grades grades, RateTable rates, Holds holds) {
        return claims.stream().filter(c -> counts(c, holds, rates.today()) && overdue(c, grades, rates)).toList();
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
     * held ones included, except that an OPEN or PENDING claim of a person whose retirement date that today has
     * reached counts in none, unless a statement binds it, and a claim {@link #deferred deferred} on that today counts in none.
     */
    public static boolean counts(Claim claim, Holds holds, LocalDate today) {
        return claim.getStatus().counts() && !deferred(claim, today) && !leftOut(claim, holds, today);
    }

    /**
     * A person's claimed: the sum of the amounts of the OPEN, PENDING and SETTLED claims, leaving out the claims
     * that correct another, a claim {@link #deferred deferred} on the sheet's today and an unbound OPEN or PENDING
     * claim once that today has reached the person's retirement date. A hold (the person's or the claim's own)
     * leaves a claim in claimed and in every total.
     */
    public static BigDecimal claimed(List<Claim> claims, Holds holds, LocalDate today) {
        return claims.stream().filter(c -> c.getCorrects() == null && c.getStatus().counts() && !deferred(c, today)
                && !retiredOut(c, holds, today)).map(Claim::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether the person's retirement leaves the claim out: unbound, OPEN or PENDING, and that today has reached it. */
    private static boolean retiredOut(Claim claim, Holds holds, LocalDate today) {
        return !bound(claim) && claim.getStatus().unpaid() && holds.retiredOn(today);
    }

    /**
     * Whether the person's retirement leaves the claim out of the live totals (see {@link #retiredOut}). A hold
     * (the person's or the claim's own) leaves out none: it only takes the person's cut of a pool.
     */
    private static boolean leftOut(Claim claim, Holds holds, LocalDate today) {
        return retiredOut(claim, holds, today);
    }

    /**
     * Whether the person is active on the sheet's today: they have an OPEN or PENDING claim that counts (see
     * {@link #counts}) dated within the 30 days up to and including that today.
     */
    public static boolean active(List<Claim> claims, Holds holds, LocalDate today) {
        LocalDate from = today.minusDays(29);
        return claims.stream().anyMatch(c -> (c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.PENDING)
                && counts(c, holds, today) && !c.getDate().isBefore(from) && !c.getDate().isAfter(today));
    }

    /**
     * The sum of the due figures of the OPEN and PENDING claims that count (see {@link #counts}) whose age on
     * the sheet's today (the days from the claim's date; a claim dated after that today is aged 0) is from
     * {@code fromDays} to {@code toDays} inclusive (0.00 with none); a claim with no figure is skipped.
     */
    public static BigDecimal aged(Grades grades, List<Claim> claims, RateTable rates, Holds holds, long fromDays,
            long toDays) {
        LocalDate today = rates.today();
        return sum(grades, claims.stream().filter(c -> c.getStatus().unpaid() && counts(c, holds, today))
                .filter(c -> {
                    long age = Math.max(0, ChronoUnit.DAYS.between(c.getDate(), today));
                    return age >= fromDays && age <= toDays;
                }).toList(), rates);
    }

    /**
     * The sum of the settled figures of the SETTLED claims dated within the 90 days up to and including the
     * sheet's today, each at the figure frozen when it was settled (0.00 with none); one with none is skipped.
     */
    public static BigDecimal recentSettled(List<Claim> claims, LocalDate today) {
        LocalDate from = today.minusDays(89);
        return claims.stream().filter(c -> c.getStatus() == ClaimStatus.SETTLED && c.getSettledDue() != null
                && !c.getDate().isBefore(from) && !c.getDate().isAfter(today)).map(Claim::getSettledDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
     * {@link #statusAsOf}) is OPEN, PENDING or SETTLED, and it is not deferred on the
     * sheet's today, as in {@link #counts}; so too by a retirement reached on that today.
     */
    public static boolean countsAsOf(Claim claim, Holds holds, LocalDate today, LocalDate end) {
        ClaimStatus then = statusAsOf(claim, end);
        return then != null && then.counts() && !deferred(claim, today) && !leftOut(claim, holds, today);
    }

    /**
     * A person's due-total (see {@link #total}); a person not on hold.
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates) {
        return total(grades, claims, rates, Holds.NONE);
    }

    /**
     * The date whose cap holds a person's due-total: the date of their latest claim that counts (see
     * {@link #counts}); the sheet's today when none does.
     */
    public static LocalDate capOn(List<Claim> claims, RateTable rates, Holds holds) {
        return claims.stream().filter(c -> counts(c, holds, rates.today())).map(Claim::getDate)
                .max(Comparator.naturalOrder()).orElse(rates.today());
    }

    /**
     * A person's due-total: their {@link #uncapped} sum held to the cap in force on {@link #capOn}, see {@link RateTable#capped}.
     * Null (n/a) when the sum is.
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return rates.capped(uncapped(grades, claims, rates, holds), capOn(claims, rates, holds));
    }

    /** What a person's {@link #uncapped} sum is above the sheet's cap: 0.00 when it is not, or is n/a. */
    public static BigDecimal excess(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return rates.excess(uncapped(grades, claims, rates, holds), capOn(claims, rates, holds));
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

    /**
     * The settled part of a person's {@link #uncapped} sum: the due figures of their SETTLED claims that count,
     * each at its frozen figure (corrections included); a claim with no figure is skipped.
     */
    public static BigDecimal settledTotal(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return part(grades, claims, rates, holds, true);
    }

    /**
     * The open part of a person's {@link #uncapped} sum: the due figures of their OPEN and PENDING claims that
     * count (a deferred or retired-and-left-out claim does not); a claim with no figure is skipped.
     */
    public static BigDecimal openTotal(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        return part(grades, claims, rates, holds, false);
    }

    /**
     * A person's score: their {@link #settledTotal settled figures}, leaving out corrections (only SETTLED
     * claims that correct nothing count), divided by 100, minus 5 for every
     * {@link #overdue overdue claim that counts}, rounded half to even to a whole number.
     */
    public static BigDecimal score(Grades grades, List<Claim> claims, RateTable rates, Holds holds) {
        List<Claim> uncorrecting = claims.stream().filter(c -> c.getCorrects() == null).toList();
        return settledTotal(grades, uncorrecting, rates, holds).movePointLeft(2)
                .subtract(BigDecimal.valueOf(5L * overdue(claims, grades, rates, holds).size()))
                .setScale(0, RoundingMode.HALF_EVEN);
    }

    /**
     * A person's parts of shared claims that count, net: what their settled and open figures gain as a holder and
     * give up as the claim's own person, and whether they hold a part of any such claim.
     */
    public record Parts(BigDecimal settled, BigDecimal open, boolean holder) {

        public static final Parts NONE = new Parts(BigDecimal.ZERO, BigDecimal.ZERO, false);

        /** The settled and open parts added up. */
        public BigDecimal total() {
            return settled.add(open);
        }

        Parts plus(BigDecimal part, boolean settledPart, boolean holding) {
            return new Parts(settledPart ? settled.add(part) : settled, settledPart ? open : open.add(part),
                    holder || holding);
        }
    }

    /**
     * A holder's part of a shared claim: the claim's due times their share divided by 100, rounded half to even to
     * two decimals.
     */
    public static BigDecimal part(BigDecimal due, BigDecimal share) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * A holder's part of an unsettled shared claim: the claim's amount times their share divided by 100, times the
     * rate of the holder's own grade in force on the claim's date divided by 100, rounded like the claim's figure;
     * null when that grade has no rate then.
     */
    public static BigDecimal part(Claim claim, BigDecimal share, Grades holder, RateTable rates) {
        BigDecimal pct = rates.on(holder.on(claim.getDate()), claim.getDate(), claim.getId());
        return RateService.due(claim.getAmount().multiply(share).movePointLeft(2), pct,
                rates.rounding(claim.isCorrection() ? claim.getRoundsBy() : claim.getDate()));
    }

    /**
     * Everyone's {@link Parts} by person id: each shared claim that counts (see {@link #counts}, by its own person's
     * holds) and has a due figure (by its own person's grades) gives each holder their part and takes the same from
     * its own person, as a settled part when it is SETTLED, else as an open part. An unsettled claim's part is
     * worked out per holder (see {@link #part(Claim, BigDecimal, Grades, RateTable)}; 0.00 when their grade has no
     * rate, and then it does not make them a holder); a SETTLED claim keeps its parts as frozen, its settled due
     * times each share (see {@link #part(BigDecimal, BigDecimal)}). A claim of a person not given (null grades or
     * holds) is skipped.
     */
    public static Map<Long, Parts> parts(Collection<Claim> claims, Function<Long, Grades> grades,
            Function<Long, Holds> holds, RateTable rates) {
        Map<Long, Parts> parts = new HashMap<>();
        for (Claim claim : claims) {
            if (claim.getShares().isEmpty()) {
                continue;
            }
            Grades owner = grades.apply(claim.getPersonId());
            Holds held = holds.apply(claim.getPersonId());
            if (owner == null || held == null || !counts(claim, held, rates.today())) {
                continue;
            }
            BigDecimal due = figure(claim, owner, rates);
            if (due == null) {
                continue;
            }
            boolean settled = claim.getStatus() == ClaimStatus.SETTLED;
            for (ClaimShare share : claim.getShares()) {
                Grades holder = grades.apply(share.getPersonId());
                BigDecimal part = settled ? part(due, share.getShare())
                        : holder == null ? null : part(claim, share.getShare(), holder, rates);
                boolean holding = part != null;
                BigDecimal counted = holding ? part : BigDecimal.ZERO.setScale(2);
                parts.merge(share.getPersonId(), Parts.NONE.plus(counted, settled, holding),
                        (a, b) -> a.plus(b.total(), settled, b.holder()));
                parts.merge(claim.getPersonId(), Parts.NONE.plus(counted.negate(), settled, false),
                        (a, b) -> a.plus(b.total(), settled, false));
            }
        }
        return parts;
    }

    /** A person's due-total (see {@link #total}) with their {@link Parts} of shared claims. */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts) {
        return rates.capped(uncapped(grades, claims, rates, holds, parts), capOn(claims, rates, holds));
    }

    /** What a person's {@link #uncapped} sum with their {@link Parts} is above the sheet's cap. */
    public static BigDecimal excess(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts) {
        return rates.excess(uncapped(grades, claims, rates, holds, parts), capOn(claims, rates, holds));
    }

    /**
     * A person's due-total (see {@link #total}) with their {@link Parts} of shared claims, held to the sheet's cap
     * less their cut total (see {@link RateTable#capped(BigDecimal, BigDecimal)}).
     */
    public static BigDecimal total(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts,
            BigDecimal cut) {
        return rates.capped(uncapped(grades, claims, rates, holds, parts), cut, capOn(claims, rates, holds));
    }

    /** What a person's {@link #uncapped} sum with their {@link Parts} plus their cut total is above the sheet's cap. */
    public static BigDecimal excess(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts,
            BigDecimal cut) {
        return rates.excess(uncapped(grades, claims, rates, holds, parts), cut, capOn(claims, rates, holds));
    }

    /**
     * A person's {@link #uncapped} sum plus their {@link Parts} of shared claims; a holder of a part has a sum
     * (0.00 for their own claims) even when their own would be n/a.
     */
    public static BigDecimal uncapped(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts) {
        BigDecimal own = uncapped(grades, claims, rates, holds);
        if (own == null && !parts.holder()) {
            return null;
        }
        return (own == null ? BigDecimal.ZERO : own).add(parts.total());
    }

    /** A person's {@link #settledTotal} with the settled part of their {@link Parts}. */
    public static BigDecimal settledTotal(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts) {
        return settledTotal(grades, claims, rates, holds).add(parts.settled());
    }

    /** A person's {@link #openTotal} with the open part of their {@link Parts}. */
    public static BigDecimal openTotal(Grades grades, List<Claim> claims, RateTable rates, Holds holds, Parts parts) {
        return openTotal(grades, claims, rates, holds).add(parts.open());
    }

    private static BigDecimal part(Grades grades, List<Claim> claims, RateTable rates, Holds holds, boolean settled) {
        return sum(grades, claims.stream().filter(c -> counts(c, holds, rates.today())
                && (c.getStatus() == ClaimStatus.SETTLED) == settled).toList(), rates);
    }
}
