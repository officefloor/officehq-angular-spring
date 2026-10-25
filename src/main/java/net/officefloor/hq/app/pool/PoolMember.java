package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A person in a pool, with their share percent of it. */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "person_id", nullable = false)
    private long personId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected PoolMember() {
    }

    PoolMember(long personId, BigDecimal share) {
        this.personId = personId;
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

    public long getPersonId() {
        return personId;
    }

    public BigDecimal getShare() {
        return share;
    }
}
