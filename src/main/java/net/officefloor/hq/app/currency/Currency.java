package net.officefloor.hq.app.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * A currency clients can be billed in, by ISO 4217 code, with the symbol its amounts are shown with and
 * the step they are rounded to when shown (0.05 rounds to the nearest five cents).
 */
@Entity
@Table(name = "currency")
public class Currency {

    /** The currency a client is billed in unless another is chosen. */
    public static final String DEFAULT = "USD";

    /** The original currencies come first in their usual order; any others follow by code. */
    private static final List<String> STANDARD = List.of("USD", "EUR", "GBP", "CAD", "AUD");

    public static final Comparator<String> ORDER = Comparator
            .comparingInt((String code) -> STANDARD.contains(code) ? STANDARD.indexOf(code) : STANDARD.size())
            .thenComparing(Comparator.naturalOrder());

    @Id
    private String code;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "rounding_step", nullable = false, precision = 10, scale = 2)
    private BigDecimal roundingStep = new BigDecimal("0.01");

    protected Currency() {
    }

    public String getCode() {
        return code;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getRoundingStep() {
        return roundingStep;
    }

    public void setRoundingStep(BigDecimal roundingStep) {
        this.roundingStep = roundingStep.setScale(2, RoundingMode.HALF_UP);
    }
}
