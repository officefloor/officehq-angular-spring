package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A line of a claim: its label, a whole quantity of at least 1 and a unit amount (two decimals). */
@Entity
@Table(name = "claim_line")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "unit", nullable = false, precision = 14, scale = 2)
    private BigDecimal unit;

    protected ClaimLine() {
    }

    public ClaimLine(String label, int qty, BigDecimal unit) {
        this.label = label;
        this.qty = qty;
        this.unit = unit;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getUnit() {
        return unit;
    }

    /** The line's amount: its quantity times its unit amount. */
    public BigDecimal amount() {
        return unit.multiply(BigDecimal.valueOf(qty));
    }
}
