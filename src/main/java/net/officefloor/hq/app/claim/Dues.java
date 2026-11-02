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
     *  else its live due; never the late charge, which keeps following the sheet's today. Null when
     *  there is none. */
    public BigDecimal bindable(String sheetId, Person person, Claim claim) {
        if (claim.isStated() && claim.getStatedDue() != null) {
            return claim.getStatedDue();
        }
        return due(sheetId, person, claim);
    }

    /** The late charge on an open claim overdue on the sheet's today (its due-by, its date plus the
     *  terms, before today): the late percent of its figure for every full 30 days it is overdue,
     *  rounded half to even to two decimals; 0.00 when it is not overdue. */
    private BigDecimal lateCharge(String sheetId, Claim claim, BigDecimal figure) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            return none;
        }
        LocalDate dueBy = claim.getDate().plusDays(sheets.terms(sheetId));
        long periods = ChronoUnit.DAYS.between(dueBy, sheets.today(sheetId)) / 30;
        if (periods <= 0) {
            return none;
        }
        return figure.multiply(sheets.latePct(sheetId)).multiply(BigDecimal.valueOf(periods)).movePointLeft(2)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Whether a claim of the person counts in the live totals: it counts, and it is not an open
     *  claim of a person on hold. */
    public static boolean countsLive(Person person, Claim claim) {
        return claim.getStatus().counts() && !(person.isHeld() && claim.getStatus() == ClaimStatus.OPEN);
    }

    /** The claims of a person that count in the live totals. */
    public static List<Claim> live(Person person, List<Claim> theirs) {
        return theirs.stream().filter(c -> countsLive(person, c)).toList();
    }

    /** A person's due-total: the sum of the figures of their claims that count; null (n/a) when
     *  their grade has no rate on the sheet's today and no counting claim keeps a fixed figure. */
    public BigDecimal dueTotal(String sheetId, Person person, List<Claim> theirs) {
        List<Claim> counted = theirs.stream().filter(c -> c.getStatus().counts()).toList();
        boolean rated = rates.findInForce(sheetId, person.getGrade(), sheets.today(sheetId)).isPresent();
        boolean fixed = counted.stream().anyMatch(c -> c.getSettledDue() != null
                || (c.isStated() && c.getStatedDue() != null));
        if (!rated && !fixed) {
            return null;
        }
        return counted.stream().map(c -> figure(sheetId, person, c)).filter(d -> d != null)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** A claim's live due at the rate for the person's grade in force on the claim's date: amount
     *  times pct divided by 100, rounded half to even to two decimals, and at least the sheet's
     *  floor; null when no rate applies on that date. */
    private BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal floor = sheets.floor(sheetId);
        return rates.findInForce(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .map(due -> due.max(floor))
                .orElse(null);
    }
}
