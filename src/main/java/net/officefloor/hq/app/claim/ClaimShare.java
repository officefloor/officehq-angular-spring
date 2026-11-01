package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A share of a claim: another person on the sheet holding a part of its due, and their share percent (two
 *  decimals, more than 0.00). */
@Embeddable
public class ClaimShare {

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected ClaimShare() {
    }

    public ClaimShare(Long personId, BigDecimal share) {
        this.personId = personId;
        this.share = share;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The holder's part of a due: the due times their share divided by 100, rounded half to even. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** The holder's part of a claim's amount at their own grade's rate: the amount times their share divided by
     *  100, times the rate divided by 100, rounded as the claim rounds. */
    public BigDecimal partOf(BigDecimal amount, BigDecimal rate, RoundingMode rounding) {
        return amount.multiply(share).multiply(rate).movePointLeft(4).setScale(2, rounding);
    }
}
