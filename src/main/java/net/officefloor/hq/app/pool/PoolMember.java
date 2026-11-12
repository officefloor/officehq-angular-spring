package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

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

    protected PoolMember() {
    }

    PoolMember(Long personId, Long memberPoolId, BigDecimal share) {
        this.personId = personId;
        this.memberPoolId = memberPoolId;
        this.share = share;
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

    void setPersonId(Long personId) {
        this.personId = personId;
    }

    void setShare(BigDecimal share) {
        this.share = share;
    }
}
