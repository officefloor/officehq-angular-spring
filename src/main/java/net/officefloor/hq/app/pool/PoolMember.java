package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A person in a pool, with their share percent of it. */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false)
    private BigDecimal share;

    protected PoolMember() {
    }

    public PoolMember(String sheetId, Long poolId, Long personId, BigDecimal share) {
        this.sheetId = sheetId;
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPoolId() {
        return poolId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }

    /** Moves the membership to another person, keeping its place in the order members were added. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }
}
