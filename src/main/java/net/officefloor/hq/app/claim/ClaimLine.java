package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A line of a DRAFT claim: a label, a whole quantity of at least 1 and a unit amount (two decimals). */
@Entity
@Table(name = "claim_line")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "label", nullable = false, updatable = false)
    private String label;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    @Column(name = "unit_amount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unit;

    protected ClaimLine() {
    }

    public ClaimLine(Long claimId, String label, int qty, BigDecimal unit) {
        this.claimId = claimId;
        this.label = label;
        this.qty = qty;
        this.unit = unit;
    }

    public Long getClaimId() {
        return claimId;
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

    /** The quantity times the unit amount. */
    public BigDecimal total() {
        return unit.multiply(BigDecimal.valueOf(qty));
    }

    /** The line as the claim shows it: <label> <qty> x <unit>. */
    public String line() {
        return label + " " + qty + " x " + unit.toPlainString();
    }
}
