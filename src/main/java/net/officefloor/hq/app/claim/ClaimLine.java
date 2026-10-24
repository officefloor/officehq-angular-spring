package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A line of a claim: a label, a quantity (a whole number, at least 1) and a unit amount (two decimals). */
@Entity
@Table(name = "claim_line")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "unit", nullable = false, precision = 12, scale = 2)
    private BigDecimal unit;

    protected ClaimLine() {
    }

    public ClaimLine(Long claimId, String label, int qty, BigDecimal unit) {
        this.claimId = claimId;
        this.label = label;
        this.qty = qty;
        this.unit = unit;
    }

    /** A copy of the line for the claim {@code claimId}. */
    public ClaimLine copyFor(Long claimId) {
        return new ClaimLine(claimId, label, qty, unit);
    }

    public Long getId() {
        return id;
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

    /** The unit amount as text, with two decimals. */
    public String unitText() {
        return unit.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** The quantity times the unit amount. */
    public BigDecimal total() {
        return unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The quantities times the unit amounts of the lines, added up. */
    public static BigDecimal totalOf(java.util.List<ClaimLine> lines) {
        return lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
