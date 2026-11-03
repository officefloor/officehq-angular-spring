package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A share of a claim held by another person of its sheet: the person and their share percent (two
 *  decimals, above 0.00). The holder's due-total counts their part of the claim's due (see
 *  {@link Dues#part}), and the claim's own person's the rest. */
@Entity
@Table(name = "claim_share")
public class ClaimShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected ClaimShare() {
    }

    public ClaimShare(String sheetId, Long personId, BigDecimal share) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.share = share;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }
}
