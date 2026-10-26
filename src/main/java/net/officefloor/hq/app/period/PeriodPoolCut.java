package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;

/** A pool member's cut fixed when its period was closed or soft-closed. */
@Embeddable
public class PeriodPoolCut {

    /** The pool, or a crew's id negated. */
    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    /** The member's key: the person's id, or pool-&lt;name&gt; for a member pool. */
    @Column(name = "member_key", nullable = false)
    private String memberKey;

    @Column(name = "cut", nullable = false)
    private BigDecimal cut;

    protected PeriodPoolCut() {
    }

    PeriodPoolCut(Long poolId, String memberKey, BigDecimal cut) {
        this.poolId = poolId;
        this.memberKey = memberKey;
        this.cut = cut;
    }

    public Long getPoolId() {
        return poolId;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public BigDecimal getCut() {
        return cut;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PeriodPoolCut that && Objects.equals(poolId, that.poolId)
                && Objects.equals(memberKey, that.memberKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(poolId, memberKey);
    }
}
