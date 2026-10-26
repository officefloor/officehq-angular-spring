package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A share of a claim: the person on the sheet holding it and their percent of the claim's due. */
@Embeddable
public class ClaimShare {

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false)
    private BigDecimal share;

    protected ClaimShare() {
    }

    ClaimShare(Long personId, BigDecimal share) {
        this.personId = personId;
        this.share = share;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The holder's part of the claim's due: the due times their share divided by 100, rounded half to even. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
    }
}
