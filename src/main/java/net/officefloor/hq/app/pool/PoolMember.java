package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A member of a pool, with their share percent (two decimals): a person, or another pool. */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The person, or null when the member is a pool. */
    @Column(name = "person_id")
    private Long personId;

    /** The member pool, or null when the member is a person. */
    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    /** The first day of the membership, or null: from the start. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day of the membership, or null: it does not end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    protected PoolMember() {
    }

    public PoolMember(Long personId, BigDecimal share) {
        this.personId = personId;
        this.share = share;
    }

    /** A pool as a member, with its share. */
    public static PoolMember ofPool(Long memberPoolId, BigDecimal share) {
        PoolMember m = new PoolMember(null, share);
        m.memberPoolId = memberPoolId;
        return m;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public Long getMemberPoolId() {
        return memberPoolId;
    }

    /** Make the membership another person's (a merge). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public BigDecimal getShare() {
        return share;
    }

    public void setShare(BigDecimal share) {
        this.share = share;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Set the membership's dates (either may be null). */
    public void setDates(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }
}
