package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A member of a pool, with its share percent of it: a person, or another pool of the sheet. */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    PoolMember(Long personId, Long memberPoolId, BigDecimal share, LocalDate fromDate, LocalDate toDate) {
        this.personId = personId;
        this.memberPoolId = memberPoolId;
        this.share = share;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public Long getId() {
        return id;
    }

    /** The person, or none when the member is a pool. */
    public Long getPersonId() {
        return personId;
    }

    /** The pool, or none when the member is a person. */
    public Long getMemberPoolId() {
        return memberPoolId;
    }

    public BigDecimal getShare() {
        return share;
    }

    /** The first day of the membership; none means from always. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last day of the membership; none means for good. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the membership's dates include a date. */
    public boolean activeOn(LocalDate date) {
        return (fromDate == null || !date.isBefore(fromDate)) && (toDate == null || !date.isAfter(toDate));
    }

    void setPersonId(Long personId) {
        this.personId = personId;
    }

    void setShare(BigDecimal share) {
        this.share = share;
    }
}
