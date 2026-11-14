package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A plan date a plan has made its claim for. */
@Entity
@Table(name = "plan_claim")
public class PlanClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate date;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** The sheet's today when the claim was made. */
    @Column(name = "made_on", nullable = false, updatable = false)
    private LocalDate madeOn;

    protected PlanClaim() {
    }

    public PlanClaim(Long planId, LocalDate date, Long claimId, LocalDate madeOn) {
        this.planId = planId;
        this.date = date;
        this.claimId = claimId;
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getDate() {
        return date;
    }

    public Long getClaimId() {
        return claimId;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }
}
