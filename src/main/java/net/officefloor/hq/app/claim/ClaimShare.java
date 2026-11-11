package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A share of a claim held by another person on the sheet: the holder and their share percent (two
 * decimals, more than 0.00). The holder's part is the claim's due times the share divided by 100,
 * rounded half to even.
 */
@Embeddable
public class ClaimShare {

    @Column(name = "holder_id", nullable = false)
    private Long holderId;

    @Column(name = "share_pct", nullable = false)
    private BigDecimal share;

    protected ClaimShare() {
    }

    public ClaimShare(Long holderId, BigDecimal share) {
        this.holderId = holderId;
        this.share = share;
    }

    public Long getHolderId() {
        return holderId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The holder's part of a due: the due times the share divided by 100, rounded half to even. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
    }
}
