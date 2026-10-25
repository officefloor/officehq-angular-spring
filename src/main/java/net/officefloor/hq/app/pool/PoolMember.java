package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A member of a pool, a person or another pool of the sheet, with its share percent of it. */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The person, or null when the member is a pool. */
    @Column(name = "person_id")
    private Long personId;

    /** The pool, or null when the member is a person. */
    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected PoolMember() {
    }

    PoolMember(Long personId, Long memberPoolId, BigDecimal share) {
        this.personId = personId;
        this.memberPoolId = memberPoolId;
        this.share = share.setScale(2);
    }

    void setPersonId(long personId) {
        this.personId = personId;
    }

    /** Add to the share, held to at most 100.00. */
    void addShare(BigDecimal more) {
        BigDecimal sum = share.add(more);
        share = sum.compareTo(HUNDRED) > 0 ? HUNDRED : sum;
    }

    public Long getId() {
        return id;
    }

    /** The person, or null when the member is a pool. */
    public Long getPersonId() {
        return personId;
    }

    /** The pool, or null when the member is a person. */
    public Long getMemberPoolId() {
        return memberPoolId;
    }

    boolean isPerson(long id) {
        return personId != null && personId == id;
    }

    public BigDecimal getShare() {
        return share;
    }
}
