package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A pool member's cut for a period, fixed when it was closed or soft-closed: the pool by its id (a crew's id negated),
 * the member as {@code person:<id>} or {@code pool:<id>}, and the cut (two decimals).
 */
@Embeddable
public class PeriodPoolCut {

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "member", nullable = false)
    private String member;

    @Column(name = "cut", nullable = false, precision = 14, scale = 2)
    private BigDecimal cut;

    protected PeriodPoolCut() {
    }

    public PeriodPoolCut(Long poolId, String member, BigDecimal cut) {
        this.poolId = poolId;
        this.member = member;
        this.cut = cut;
    }

    public Long getPoolId() {
        return poolId;
    }

    public String getMember() {
        return member;
    }

    public BigDecimal getCut() {
        return cut;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeriodPoolCut other && Objects.equals(poolId, other.poolId)
                && Objects.equals(member, other.member);
    }

    @Override
    public int hashCode() {
        return Objects.hash(poolId, member);
    }
}
