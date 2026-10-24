package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A person's (or another pool's) membership of a pool, with their share percent of it and the dates it runs
 * (each optional, both included). */
@Entity
@Table(name = "sheet_pool_member")
public class SheetPoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    /** The person who is the member; null when the member is a pool. */
    @Column(name = "person_id")
    private Long personId;

    /** The pool that is the member; null when the member is a person. */
    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    /** The first day of the membership; null when it has no start. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day of the membership; null when it has no end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    protected SheetPoolMember() {
    }

    public SheetPoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool's membership of the pool, with its share. */
    public static SheetPoolMember ofPool(Long poolId, Long memberPoolId, BigDecimal share) {
        SheetPoolMember member = new SheetPoolMember(poolId, null, share);
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

    public BigDecimal getShare() {
        return share;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Set the dates the membership runs, each null for none. */
    public SheetPoolMember dated(LocalDate from, LocalDate to) {
        this.fromDate = from;
        this.toDate = to;
        return this;
    }

    /** Whether the membership's dates include {@code day} (always, when the day is unknown). */
    public boolean isOn(LocalDate day) {
        if (day == null) return true;
        return (fromDate == null || !day.isBefore(fromDate)) && (toDate == null || !day.isAfter(toDate));
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }
}
