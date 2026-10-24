package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A person's membership of a pool, with their share percent of it. */
@Entity
@Table(name = "sheet_pool_member")
public class SheetPoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected SheetPoolMember() {
    }

    public SheetPoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    public Long getId() {
        return id;
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

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }
}
