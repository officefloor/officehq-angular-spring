package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A person a claim is shared with, and the share of its due they hold: a percent with two decimals. */
@Entity
@Table(name = "claim_share")
public class ClaimShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected ClaimShare() {
    }

    public ClaimShare(String sheetId, Long claimId, Long personId, BigDecimal share) {
        this.sheetId = sheetId;
        this.claimId = claimId;
        this.personId = personId;
        this.share = share;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }

    /** The holder's part of a due: the due times the share divided by 100, rounded half to even to two decimals. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
