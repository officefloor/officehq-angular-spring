package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import org.springframework.stereotype.Component;

/** What is due on a claim, read from the rates its person's grade has. */
@Component
public class Dues {

    private final RateRepository rates;

    public Dues(RateRepository rates) {
        this.rates = rates;
    }

    /**
     * The due figure a claim shows: the one fixed when it was settled, else the one its person's last
     * statement bound it to, else its amount at the rate the grade has in force on the claim's date;
     * null (n/a) when there is none.
     */
    public BigDecimal figure(Claim claim, String grade) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : live(claim, grade);
    }

    /** A claim's amount at the rate the grade has in force on the claim's date; null when it has none. */
    public BigDecimal live(Claim claim, String grade) {
        return rates.findInForce(claim.getSheetId(), grade, claim.getDate())
                .map(rate -> due(claim.getAmount(), rate))
                .orElse(null);
    }

    /** What is due on an amount at a rate: the amount times the percent over 100, rounded half to even. */
    private static BigDecimal due(BigDecimal amount, Rate rate) {
        return amount.multiply(rate.getPct()).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
