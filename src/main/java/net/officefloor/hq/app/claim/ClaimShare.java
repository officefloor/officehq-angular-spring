package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A share of a claim: another person on the sheet holding a percent (two decimals, above 0.00) of its due. */
@Embeddable
public class ClaimShare {

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share_pct", nullable = false)
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

    /** The holder's part of a due: the due times the share divided by 100, rounded half to even to cents. */
    public BigDecimal part(BigDecimal due) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
