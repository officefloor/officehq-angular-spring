package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A pool's member: a person on the sheet, in the pool at most once, or another pool (a member of at most
 * one pool), with a share percent and an optional from-date and to-date it is in force between. Exactly
 * one of the person and the member pool is set.
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

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

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

    /** The first day the membership is in force; null when it has no start. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last day the membership is in force; null when it has no end. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Set the days the membership is in force between, either end optional. */
    public PoolMember during(LocalDate from, LocalDate to) {
        this.fromDate = from;
        this.toDate = to;
        return this;
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
