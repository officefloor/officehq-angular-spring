package net.officefloor.hq.app.taxadjustment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manual adjustment to the tax owed for the period its date falls in: positive adds to the tax owed, negative
 * takes from it. The amount is in the home currency at the time it was recorded.
 */
@Entity
@Table(name = "tax_adjustment")
public class TaxAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 200)
    private String reason;

    protected TaxAdjustment() {
    }

    public TaxAdjustment(LocalDate adjustmentDate, BigDecimal amount, String currency, String reason) {
        this.adjustmentDate = adjustmentDate;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getAdjustmentDate() {
        return adjustmentDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }
}
