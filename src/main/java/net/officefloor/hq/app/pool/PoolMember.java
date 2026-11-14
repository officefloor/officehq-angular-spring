package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A membership of a pool, a person's or another pool's, with its share percent (two decimals). */
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

    @Column(name = "share", nullable = false)
    private BigDecimal share;

    /** The first day of the membership, included; null when it has no start. */
    @Column(name = "from_date")
    private LocalDate from;

    /** The last day of the membership, included; null when it does not end. */
    @Column(name = "to_date")
    private LocalDate to;

    /** The sheet's today when the membership was added; null for one added before this was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected PoolMember() {
    }

    public PoolMember(Long poolId, Long personId, BigDecimal share) {
        this.poolId = poolId;
        this.personId = personId;
        this.share = share;
    }

    /** A pool's membership of another pool. */
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

    public Long getMemberPoolId() {
        return memberPoolId;
    }

    /** Make the membership another person's. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Add a share to the member's share. */
    public void addShare(BigDecimal more) {
        this.share = share.add(more);
    }

    public BigDecimal getShare() {
        return share;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Date the membership: from its from-date to its to-date, both included (null: no bound). */
    public PoolMember dated(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
        return this;
    }

    /** Keep the sheet's today the membership is added on. */
    public PoolMember madeOn(LocalDate day) {
        this.madeOn = day;
        return this;
    }

    /** Whether the membership stood at the end of a day: it was added while the sheet's today was on or
     *  before it (one added before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
