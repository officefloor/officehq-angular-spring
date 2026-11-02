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

    /** The figure a statement binds a claim to: the one its person's last statement bound it to, or
     *  else its live due (a pending claim is not bound by a statement while it is pending); never
     *  the late charge, which keeps following the sheet's today. Null when there is none. */
    public BigDecimal bindable(String sheetId, Person person, Claim claim) {
        if (claim.isStated() && claim.getStatedDue() != null && claim.getStatus() != ClaimStatus.PENDING) {
            return claim.getStatedDue();
        }
        return due(sheetId, person, claim);
    }

    /** The late charge on an open (or pending) claim overdue on the sheet's today (its due-by, fixed when it was
     *  made, before today; a correction has none): the late percent of its figure for every full 30 days it is overdue,
     *  rounded to two decimals by the claim's {@link #rounding}; 0.00 when it is not overdue. */
    private BigDecimal lateCharge(String sheetId, Claim claim, BigDecimal figure) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!claim.getStatus().isOpen() || claim.isCorrection()) {
            return none;
        }
        long periods = ChronoUnit.DAYS.between(claim.getDueBy(), sheets.today(sheetId)) / 30;
        if (periods <= 0) {
            return none;
        }
        return figure.multiply(sheets.latePct(sheetId)).multiply(BigDecimal.valueOf(periods)).movePointLeft(2)
                .setScale(2, rounding(sheetId, claim));
    }

    /** Whether a claim of the person counts in the live totals: it counts, and it is not an open
     *  (or pending) claim of a person on hold. */
    public static boolean countsLive(Person person, Claim claim) {
        return claim.getStatus().counts() && !(person.isHeld() && claim.getStatus().isOpen());
    }

    /** The claims of a person that count in the live totals. */
    public static List<Claim> live(Person person, List<Claim> theirs) {
        return theirs.stream().filter(c -> countsLive(person, c)).toList();
    }

    /** A person's due-total: the sum of the figures of their claims that count; null (n/a) when
     *  their grade on the sheet's today has no rate on it and no counting claim has a figure (one
     *  fixed, or a live due at the grade in force on its date). */
    public BigDecimal dueTotal(String sheetId, Person person, List<Claim> theirs) {
        List<BigDecimal> figures = theirs.stream().filter(c -> c.getStatus().counts())
                .map(c -> figure(sheetId, person, c)).filter(d -> d != null).toList();
        LocalDate today = sheets.today(sheetId);
        boolean rated = rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
        if (!rated && figures.isEmpty()) {
            return null;
        }
        return figures.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** A claim's live due at the rate, in force on the claim's date, for the person's grade in force
     *  on that date: amount
     *  times pct divided by 100, rounded to two decimals by the claim's {@link #rounding}, and at least the sheet's
     *  floor (a correction has no floor); null when no rate applies on that date. */
    private BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal floor = sheets.floor(sheetId);
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
