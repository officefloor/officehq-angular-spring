package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A person, or another pool, in a pool, at most once, with their share percent (two decimals). */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The person; null when the member is a pool. */
    @Column(name = "person_id")
    private Long personId;

    /** The pool that is the member; null when the member is a person. */
    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "share", nullable = false, precision = 5, scale = 2)
    private BigDecimal share;

    protected PoolMember() {}

    public PoolMember(Long poolId, String sheetId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.sheetId = sheetId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool as a member of another. */
    public static PoolMember ofPool(Long poolId, String sheetId, Long memberPoolId, BigDecimal share) {
        PoolMember m = new PoolMember(poolId, sheetId, null, share);
        m.memberPoolId = memberPoolId;
        return m;
    }

    public Long getId() { return id; }

    public Long getPoolId() { return poolId; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public Long getMemberPoolId() { return memberPoolId; }

    public BigDecimal getShare() { return share; }

    /** Make the membership another person's. */
    public void setPersonId(Long personId) { this.personId = personId; }

    /** Add a share to theirs. */
    public void addShare(BigDecimal more) { this.share = share.add(more); }
}
