package net.officefloor.hq.app.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An exchange rate: from its date (until the currency's next rate), one unit of the currency is worth
 * {@link #getRate()} units of the home currency.
 */
@Entity
@Table(name = "fx_rate")
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    protected FxRate() {
    }

    public Long getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public BigDecimal getRate() {
        return rate;
    }
}
