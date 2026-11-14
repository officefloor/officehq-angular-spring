package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A membership of a pool, a person's or another pool's, with its share percent (two decimals). */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    /** The member person; null when the member is a pool. */
    @Column(name = "person_id")
    private Long personId;

    /** The member pool; null when the member is a person. */
    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false)
    private BigDecimal share;

    protected PoolMember() {
    }

    public PoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool's membership of another pool. */
    public static PoolMember ofPool(Long poolId, Long memberPoolId, BigDecimal share) {
        PoolMember member = new PoolMember(poolId, null, share);
        member.memberPoolId = memberPoolId;
        return member;
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

    public Long getMemberPoolId() {
        return memberPoolId;
    }

    /** Make the membership another person's. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Add a share to the member's share. */
    public void addShare(BigDecimal more) {
        this.share = share.add(more);
    }

    public BigDecimal getShare() {
        return share;
    }
}
