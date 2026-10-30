package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A plan date a claim was made for, and the claim: each plan date has at most one. */
@Entity
@Table(name = "sheet_plan_claim")
public class PlanClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "claim_id")
    private Long claimId;

    protected PlanClaim() {
    }

    public PlanClaim(Long planId, LocalDate on, Long claimId) {
        this.planId = planId;
        this.on = on;
        this.claimId = claimId;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getOn() {
        return on;
    }

    /** The claim made for the plan date (null when it was made before it was kept). */
    public Long getClaimId() {
        return claimId;
    }
}
