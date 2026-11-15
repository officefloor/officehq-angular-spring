package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;

/** A plan date a plan has made its claim for. */
@Entity
@Table(name = "plan_claim")
@IdClass(PlanClaim.Key.class)
public class PlanClaim {

    public record Key(Long planId, LocalDate onDate) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Id
    @Column(name = "on_date", nullable = false)
    private LocalDate onDate;

    @Column(name = "claim_id", nullable = false)
    private long claimId;

    protected PlanClaim() {
    }

    public PlanClaim(long planId, LocalDate onDate, long claimId) {
        this.planId = planId;
        this.onDate = onDate;
        this.claimId = claimId;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getOnDate() {
        return onDate;
    }

    public long getClaimId() {
        return claimId;
    }
}
