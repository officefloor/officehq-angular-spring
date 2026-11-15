package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A person, or another pool, in a pool with their share percent (two decimals, more than 0.00,
 *  at most 100.00): exactly one of the person and the member pool is set. The membership may carry
 *  a from-date and a to-date (both inclusive, either optional). */
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

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
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

    /** A pool as a member of another pool, with a share. */
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

    /** The pool that is this member, null for a person. */
    public Long getMemberPoolId() {
        return memberPoolId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** Move the membership to another person. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }

    /** The first day of the membership, null when it has no start. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last day of the membership, null when it has no end. */
    public LocalDate getToDate() {
        return toDate;
    }

    public void setDates(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }
}
