package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A line of a claim: a label, a quantity (a whole number, at least 1) and a unit amount (two decimals). */
@Entity
@Table(name = "claim_line")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

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

    public ClaimLine(String sheetId, Long claimId, String label, int qty, BigDecimal unit) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.label = label;
        this.qty = qty;
        this.unit = unit;
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

    /** The quantity times the unit amount. */
    public BigDecimal total() {
        return unit.multiply(BigDecimal.valueOf(qty));
    }
}
