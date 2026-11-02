package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A person's (or another pool's) membership of a pool, with their share percent (two decimals, above 0 and at most 100). */
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

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    /** The first day of the membership; null: no start. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day of the membership; null: no end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    protected PoolMember() {
    }

    public PoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool's membership of a pool. */
    public static PoolMember ofPool(Long poolId, Long memberPoolId, BigDecimal share) {
        PoolMember m = new PoolMember(poolId, null, share);
        m.memberPoolId = memberPoolId;
        return m;
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

    public BigDecimal getShare() {
        return share;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Set the days the membership runs (either may be null: open-ended). */
    public void setDates(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    /** Whether the membership's dates include the day. */
    public boolean isInForceOn(LocalDate day) {
        return (fromDate == null || !day.isBefore(fromDate)) && (toDate == null || !day.isAfter(toDate));
    }

    /** Make the membership another person's (the member was merged into them). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Add a share to the member's own (a person merged into them was a member too). */
    public void addShare(BigDecimal more) {
        this.share = share.add(more);
    }
}
