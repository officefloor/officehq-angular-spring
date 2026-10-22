package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A pool's member: a person on the sheet, in the pool at most once, or another pool (a member of at most
 * one pool), with a share percent. Exactly one of the person and the member pool is set.
 */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected PoolMember() {
    }

    public PoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool as a member of another pool, with a share percent. */
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

    /** The pool that is the member; null when the member is a person. */
    public Long getMemberPoolId() {
        return memberPoolId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** Move the membership to another person on the sheet, its share kept. */
    public void moveTo(Long personId) {
        this.personId = personId;
    }

    /** Add a share to the member's own. */
    public void addShare(BigDecimal more) {
        this.share = share.add(more);
    }
}
