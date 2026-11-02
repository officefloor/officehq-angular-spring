package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * A pool member's cut fixed when its period was closed or soft-closed: the pool (or the crew, for a
 * crew's pool), the member (a person or a pool), and its cut (null: none).
 */
@Embeddable
public class PoolCutLine {

    @Column(name = "pool_id")
    private Long poolId;

    @Column(name = "crew_id")
    private Long crewId;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "member_pool_id")
    private Long memberPoolId;

    @Column(name = "cut", precision = 14, scale = 2)
    private BigDecimal cut;

    protected PoolCutLine() {}

    public PoolCutLine(Long poolId, Long crewId, Long personId, Long memberPoolId, BigDecimal cut) {
        this.poolId = poolId;
        this.crewId = crewId;
        this.personId = personId;
        this.memberPoolId = memberPoolId;
        this.cut = cut;
    }

    public Long getPoolId() { return poolId; }

    public Long getCrewId() { return crewId; }

    public Long getPersonId() { return personId; }

    public Long getMemberPoolId() { return memberPoolId; }

    public BigDecimal getCut() { return cut; }
}
