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
 * A member of a pool with their share percent: a person (in a pool at most once) or another pool (a
 * member of at most one pool).
 */
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

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false)
    private BigDecimal share;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected PoolMember() {
    }

    public PoolMember(String sheetId, Long poolId, Long personId, BigDecimal share) {
        this.sheetId = sheetId;
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool as a member of another. */
    public static PoolMember ofPool(String sheetId, Long poolId, Long memberPoolId, BigDecimal share) {
        PoolMember m = new PoolMember(sheetId, poolId, null, share);
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

    /** The pool that is this member, null for a person. */
    public Long getMemberPoolId() {
        return memberPoolId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The first day of the membership; none: from the beginning of time. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last day of the membership; none: open-ended. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Date the membership from its from-date to its to-date, both inclusive, either none. */
    public PoolMember during(LocalDate from, LocalDate to) {
        this.fromDate = from;
        this.toDate = to;
        return this;
    }

    /** Whether the membership's dates include the day (always, with no day). */
    public boolean covers(LocalDate date) {
        return date == null || ((fromDate == null || !date.isBefore(fromDate))
                && (toDate == null || !date.isAfter(toDate)));
    }

    /** Move the membership to another person (a merge). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Add a share to the member's (a merge into a person already a member). */
    public void addShare(BigDecimal more) {
        this.share = this.share.add(more);
    }
}
