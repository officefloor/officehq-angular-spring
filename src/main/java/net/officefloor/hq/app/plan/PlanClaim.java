package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A plan date a claim was made for: each plan date has at most one. */
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

    protected PlanClaim() {
    }

    public PlanClaim(Long planId, LocalDate on) {
        this.planId = planId;
        this.on = on;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getOn() {
        return on;
    }
}
