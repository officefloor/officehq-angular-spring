package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/** The due figures of claims, and the due-totals of people, at the rates on a sheet. */
@Service
public class Dues {

    private final RateRepository rates;
    private final SheetService sheets;

    public Dues(RateRepository rates, SheetService sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /** The due figure a claim shows and totals: the one fixed when it was settled, or else its
     *  {@link #bindable} figure with the late charge on top while it is overdue; null when there is
     *  none. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal figure = bindable(sheetId, person, claim);
        return figure == null ? null : figure.add(lateCharge(sheetId, claim, figure));
    }

    /** The figure a claim would have today as an open claim bound by no statement: its live due with
     *  the late charge on top while it is overdue; null when there is none. */
    public BigDecimal openFigure(String sheetId, Person person, Claim claim) {
        BigDecimal figure = due(sheetId, person, claim);
        return figure == null ? null : figure.add(lateCharge(sheetId, claim, figure, true));
    }

    /** The figure a statement binds a claim to: the one its person's last statement bound it to, or
     *  else its live due (a pending claim is not bound by a statement while it is pending); never
     *  the late charge, which keeps following the sheet's today. Null when there is none, or when
     *  the statement bound it to n/a. */
    public BigDecimal bindable(String sheetId, Person person, Claim claim) {
        if (bound(claim) && (claim.getStatedDue() != null || claim.isStatedNa())) {
            return claim.getStatedDue();
        }
        return due(sheetId, person, claim);
    }

    /** Whether a statement binds a claim to n/a: its person's last statement bound it to n/a, or
     *  else it has no bound figure and no live due because the rate that would apply to it on its
     *  date (for the person's grade on that date) has ended. */
    public boolean bindsNa(String sheetId, Person person, Claim claim) {
        if (bound(claim) && (claim.getStatedDue() != null || claim.isStatedNa())) {
            return claim.isStatedNa();
        }
        return due(sheetId, person, claim) == null
                && rates.endedBefore(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
    }

    /** Whether a claim is bound by its person's last statement now (a pending claim is not). */
    private static boolean bound(Claim claim) {
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING;
    }

    /** The late charge on an open (or pending) claim overdue on the sheet's today (its due-by, fixed when it was
     *  made, before today; a correction has none): for each full 30 days it is overdue, the late percent in force on
     *  that boundary (its due-by plus 30, 60, 90 ... days) of its figure, rounded to two decimals by the claim's
     *  {@link #rounding}, the charges added up (a boundary whose percent is 0.00 adds nothing); 0.00 when it is not overdue. */
    private BigDecimal lateCharge(String sheetId, Claim claim, BigDecimal figure) {
        return lateCharge(sheetId, claim, figure, claim.getStatus().isOpen());
    }

    /** The late charge on a claim taken as open or not, as {@link #lateCharge(String, Claim, BigDecimal)}. */
    private BigDecimal lateCharge(String sheetId, Claim claim, BigDecimal figure, boolean open) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!open || claim.isCorrection() || claim.isDeferredOn(sheets.today(sheetId))) {
            return none;
        }
        long periods = ChronoUnit.DAYS.between(claim.getDueBy(), sheets.today(sheetId)) / 30;
        if (periods <= 0) {
            return none;
        }
        RoundingMode rounding = rounding(sheetId, claim);
        BigDecimal charge = none;
        for (long n = 1; n <= periods; n++) {
            BigDecimal pct = sheets.latePct(sheetId, claim.getDueBy().plusDays(30 * n));
            if (pct.signum() != 0) {
                charge = charge.add(figure.multiply(pct).movePointLeft(2).setScale(2, rounding));
            }
        }
        return charge;
    }

    /** Whether a claim of the person counts in the live totals on the sheet's today: it counts, it is
     *  not deferred on that day, and it is not an open (or pending) claim, unbound by a statement,
     *  dated within one of the person's holds. */
    public static boolean countsLive(Person person, Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isStated() && person.isHeldOn(claim.getDate()));
    }

    /** Whether a claim of the person counts in a period's totals: by the status it had at the end of
     *  the period's last day (see Claim#statusOn) it counts, and the live rules do not leave it out:
     *  it is not deferred on the sheet's today, and it is not a claim open (or pending) now,
     *  unbound by a statement, dated within one of the person's holds. */
    public static boolean countsAsOf(Person person, Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus then = claim.statusOn(end);
        return then != null && then.counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isStated() && person.isHeldOn(claim.getDate()));
    }

    /** The claims of a person that count in the live totals on the sheet's today. */
    public static List<Claim> live(Person person, List<Claim> theirs, LocalDate today) {
        return theirs.stream().filter(c -> countsLive(person, c, today)).toList();
    }

    /** A person's due-total: the sum of the figures of their claims that count, at most the sheet's
     *  cap in force on its today; null (n/a) when their grade on the sheet's today has no rate on it and no counting claim
     *  has a figure (one fixed, or a live due at the grade in force on its date). */
    public BigDecimal dueTotal(String sheetId, Person person, List<Claim> theirs) {
        List<BigDecimal> figures = theirs.stream().filter(c -> c.getStatus().counts())
                .map(c -> figure(sheetId, person, c)).filter(d -> d != null).toList();
        LocalDate today = sheets.today(sheetId);
        boolean rated = rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
        if (!rated && figures.isEmpty()) {
            return null;
        }
        BigDecimal sum = figures.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal cap = sheets.cap(sheetId);
        return cap == null ? sum : sum.min(cap);
    }

    /** A claim's live due at the rate, in force on the claim's date, for the person's grade in force
     *  on that date: amount
     *  times pct divided by 100, rounded to two decimals by the claim's {@link #rounding}, and at least the sheet's
     *  floor in force on the claim's date (a correction has no floor); null when no rate applies on that date. */
    private BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal floor = sheets.floor(sheetId, claim.getDate());
        RoundingMode rounding = rounding(sheetId, claim);
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .map(due -> claim.isCorrection() ? due : due.max(floor))
                .orElse(null);
    }

    /** How a claim's live figures round: half up when the date it rounds by (a correction's is that of
     *  the claim it corrects) is before the sheet's policy date, else half to even. */
    private RoundingMode rounding(String sheetId, Claim claim) {
        return claim.roundsBy().isBefore(sheets.policyDateInForce(sheetId)) ? RoundingMode.HALF_UP
                : RoundingMode.HALF_EVEN;
    }
}
