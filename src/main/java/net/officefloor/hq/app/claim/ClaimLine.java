package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** A line of a claim: a label, a whole quantity of at least 1 and a unit amount (two decimals). */
@Embeddable
public class ClaimLine {

    @Column(name = "line_label", nullable = false)
    private String label;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "unit_amount", nullable = false)
    private BigDecimal unit;

    protected ClaimLine() {
    }

    public ClaimLine(String label, int qty, BigDecimal unit) {
        this.label = label;
        this.qty = qty;
        this.unit = unit;
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

    /** Its quantity times its unit amount. */
    public BigDecimal total() {
        return unit.multiply(BigDecimal.valueOf(qty));
    }
}
