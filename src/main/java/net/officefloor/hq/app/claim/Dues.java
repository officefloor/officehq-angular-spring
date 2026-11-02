package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** The due figure a claim shows and totals: the one fixed when it was settled, or else the one
     *  its person's last statement bound it to, or else its live due; null when there is none. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        if (claim.isStated() && claim.getStatedDue() != null) {
            return claim.getStatedDue();
        }
        return due(sheetId, person, claim);
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

    /** A claim's due at the rate for the person's grade in force on the claim's date: amount times
     *  pct divided by 100, rounded half to even to two decimals; null when no rate applies on that date. */
    private BigDecimal due(String sheetId, Person person, Claim claim) {
        return rates.findInForce(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }
}
