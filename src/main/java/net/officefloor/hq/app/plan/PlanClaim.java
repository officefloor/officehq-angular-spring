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

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    protected PlanClaim() {
    }

    public PlanClaim(Long planId, LocalDate date, Long claimId) {
        this.planId = planId;
        this.date = date;
        this.claimId = claimId;
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
}
