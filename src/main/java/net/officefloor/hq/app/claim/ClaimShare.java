package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A share of a claim held by another person on its sheet: a percent (two decimals, more than 0.00) of
 * what is due on the claim, which counts in the holder's due-total instead of the claim's own person's. */
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

    public String getSheetId() {
        return sheetId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Pass the share to another holder. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The share as text, with two decimals. */
    public String shareText() {
        return share.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** The holder's part of {@code due}: {@code due} times the share divided by 100, rounded half to even. */
    public BigDecimal partOf(BigDecimal due) {
        return due.multiply(share).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
