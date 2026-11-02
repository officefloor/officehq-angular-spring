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

/** The due figures of claims, and the due-totals of people, at the rates on a sheet: as they are
 *  now, or on any other {@link Basis} (such as the sheet as it stood at the end of an earlier day). */
@Service
public class Dues {

    /** What a sheet's figures are worked out from: its today, its dated settings and its rates. */
    public interface Basis {

        /** The day that stands in for the sheet's today. */
        LocalDate today();

        /** The floor in force on a day. */
        BigDecimal floor(LocalDate day);

        /** The late percent in force on a day. */
        BigDecimal latePct(LocalDate day);

        /** The cap in force on the basis's today; null when there is none. */
        BigDecimal cap();

        /** The policy date in force: the one set, or else the basis's today. */
        LocalDate policyDateInForce();

        /** The rates of a grade, the latest from-date first and the one with none last. */
        List<Rate> rates(String grade);
    }

    private final RateRepository rates;
    private final SheetService sheets;

    public Dues(RateRepository rates, SheetService sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /** The sheet as it is now: its today, its settings and its rates. */
    public Basis now(String sheetId) {
        return new Basis() {
            @Override
            public LocalDate today() {
                return sheets.today(sheetId);
            }

            @Override
            public BigDecimal floor(LocalDate day) {
                return sheets.floor(sheetId, day);
            }

            @Override
            public BigDecimal latePct(LocalDate day) {
                return sheets.latePct(sheetId, day);
            }

            @Override
            public BigDecimal cap() {
                return sheets.cap(sheetId);
            }

            @Override
            public LocalDate policyDateInForce() {
                return sheets.policyDateInForce(sheetId);
            }

            @Override
            public List<Rate> rates(String grade) {
                return rates.findLatestFirst(sheetId, grade);
            }
        };
    }

    /** The due figure a claim shows and totals: the one fixed when it was settled, or else its
     *  {@link #bindable} rate figure with the late charge on that figure on top while it is overdue,
     *  at least the sheet's floor in force on the claim's date (a correction has neither); null when
     *  there is none. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        return figure(now(sheetId), person, claim);
    }

    /** The due figure a claim shows and totals on a basis (see {@link #figure(String, Person, Claim)}). */
    public BigDecimal figure(Basis basis, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return floored(basis, claim, bindable(basis, person, claim), claim.getStatus().isOpen());
    }

    /** The figure a claim would have today as an open claim bound by no statement: its rate figure
     *  with the late charge on top while it is overdue, at least the floor in force on its date; null
     *  when there is none. */
    public BigDecimal openFigure(String sheetId, Person person, Claim claim) {
        Basis basis = now(sheetId);
        return floored(basis, claim, due(basis, person, claim), true);
    }

    /** A rate figure with its late charge (taken as open or not) on top, then at least the sheet's
     *  floor in force on the claim's date (a correction has no floor); null when the figure is. */
    private BigDecimal floored(Basis basis, Claim claim, BigDecimal figure, boolean open) {
        if (figure == null) {
            return null;
        }
        BigDecimal charged = figure.add(lateCharge(basis, claim, figure, open));
        return claim.isCorrection() ? charged : charged.max(basis.floor(claim.getDate()));
    }

    /** The rate figure a statement binds a claim to: the one its person's last statement bound it to,
     *  or else its live rate figure (a pending claim is not bound by a statement while it is pending);
     *  never the late charge, which keeps following the sheet's today, nor the floor, which is applied
     *  last. Null when there is none, or when the statement bound it to n/a. */
    public BigDecimal bindable(String sheetId, Person person, Claim claim) {
        return bindable(now(sheetId), person, claim);
    }

    /** The rate figure a statement binds a claim to on a basis (see {@link #bindable(String, Person, Claim)}). */
    public BigDecimal bindable(Basis basis, Person person, Claim claim) {
        if (bound(claim) && (claim.getStatedDue() != null || claim.isStatedNa())) {
            return claim.getStatedDue();
        }
        return due(basis, person, claim);
    }

    /** Whether a statement binds a claim to n/a: its person's last statement bound it to n/a, or
     *  else it has no bound figure and no live due: no rate applies to it on its date (for the
     *  person's grade on that date), none ever having been set or the one that would having ended. */
    public boolean bindsNa(String sheetId, Person person, Claim claim) {
        return bindsNa(now(sheetId), person, claim);
    }

    /** Whether a statement binds a claim to n/a on a basis (see {@link #bindsNa(String, Person, Claim)}). */
    public boolean bindsNa(Basis basis, Person person, Claim claim) {
        if (bound(claim) && (claim.getStatedDue() != null || claim.isStatedNa())) {
            return claim.isStatedNa();
        }
        return due(basis, person, claim) == null;
    }

    /** Whether a claim is bound by its person's last statement now (a pending claim is not). */
    private static boolean bound(Claim claim) {
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING;
    }

    /** The late charge on a claim taken as open (or pending) or not, overdue on the sheet's today (its due-by, fixed when it was
     *  made, before today; a correction has none): for each full 30 days it is overdue, the late percent in force on
     *  that boundary (its due-by plus 30, 60, 90 ... days) of its figure, rounded to two decimals by the claim's
     *  {@link #rounding}, the charges added up (a boundary whose percent is 0.00 adds nothing); 0.00 when it is not overdue. */
    private BigDecimal lateCharge(Basis basis, Claim claim, BigDecimal figure, boolean open) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        LocalDate today = basis.today();
        if (!open || claim.isCorrection() || claim.isDeferredOn(today)) {
            return none;
        }
        long periods = ChronoUnit.DAYS.between(claim.getDueBy(), today) / 30;
        if (periods <= 0) {
            return none;
        }
        RoundingMode rounding = rounding(basis, claim);
        BigDecimal charge = none;
        for (long n = 1; n <= periods; n++) {
            BigDecimal pct = basis.latePct(claim.getDueBy().plusDays(30 * n));
            if (pct.signum() != 0) {
                charge = charge.add(figure.multiply(pct).movePointLeft(2).setScale(2, rounding));
            }
        }
        return charge;
    }

    /** Whether a claim of the person counts in the live totals on the sheet's today: it counts, it is
     *  not deferred on that day, and it is not an open (or pending) claim, unbound by a statement,
     *  dated within one of the person's holds or of a person retired on that day. */
    public static boolean countsLive(Person person, Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !claim.isDeferredOn(today) && !leftOut(person, claim, today);
    }

    /** Whether a claim of the person counts in a period's totals: by the status it had at the end of
     *  the period's last day (see Claim#statusOn) it counts, and the live rules do not leave it out:
     *  it is not deferred on the sheet's today, and it is not a claim open (or pending) now,
     *  unbound by a statement, dated within one of the person's holds or of a person retired on the
     *  sheet's today. */
    public static boolean countsAsOf(Person person, Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus then = claim.statusOn(end);
        return then != null && then.counts() && !claim.isDeferredOn(today) && !leftOut(person, claim, today);
    }

    /** Whether the live rules leave a claim of the person out: it is open (or pending) now, unbound
     *  by a statement, and dated within one of the person's holds or the person is retired on the
     *  sheet's today. */
    private static boolean leftOut(Person person, Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && !claim.isStated()
                && (person.isHeldOn(claim.getDate()) || person.isRetiredOn(today));
    }

    /** The claims of a person that count in the live totals on the sheet's today. */
    public static List<Claim> live(Person person, List<Claim> theirs, LocalDate today) {
        return theirs.stream().filter(c -> countsLive(person, c, today)).toList();
    }

    /** A person's due-total: the sum of the figures of their claims that count, at most the sheet's
     *  cap in force on its today; null (n/a) when their grade on the sheet's today has no rate on it and no counting claim
     *  has a figure (one fixed, or a live due at the grade in force on its date). */
    public BigDecimal dueTotal(String sheetId, Person person, List<Claim> theirs) {
        return dueTotal(now(sheetId), person, theirs);
    }

    /** A person's due-total on a basis (see {@link #dueTotal(String, Person, List)}). */
    public BigDecimal dueTotal(Basis basis, Person person, List<Claim> theirs) {
        List<BigDecimal> figures = theirs.stream().filter(c -> c.getStatus().counts())
                .map(c -> figure(basis, person, c)).filter(d -> d != null).toList();
        LocalDate today = basis.today();
        boolean rated = RateRepository.inForce(basis.rates(person.gradeOn(today)), today).isPresent();
        if (!rated && figures.isEmpty()) {
            return null;
        }
        BigDecimal sum = figures.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal cap = basis.cap();
        return cap == null ? sum : sum.min(cap);
    }

    /** A claim's live due at the rate, in force on the claim's date, for the person's grade in force
     *  on that date: amount
     *  times pct divided by 100, rounded to two decimals by the claim's {@link #rounding} (the floor is
     *  applied later, see {@link #floored}); null when no rate applies on that date. */
    private BigDecimal due(Basis basis, Person person, Claim claim) {
        RoundingMode rounding = rounding(basis, claim);
        return RateRepository.inForce(basis.rates(person.gradeOn(claim.getDate())), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .orElse(null);
    }

    /** How a claim's live figures round: half up when the date it rounds by (a correction's is that of
     *  the claim it corrects) is before the sheet's policy date, else half to even. */
    private RoundingMode rounding(Basis basis, Claim claim) {
        return claim.roundsBy().isBefore(basis.policyDateInForce()) ? RoundingMode.HALF_UP
                : RoundingMode.HALF_EVEN;
    }
}
