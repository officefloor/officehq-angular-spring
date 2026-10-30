package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A change of a plan's amount: made on a day (the sheet's today), to the new amount. */
@Entity
@Table(name = "sheet_plan_change")
public class PlanChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    protected PlanChange() {
    }

    public PlanChange(Long planId, LocalDate changedOn, BigDecimal amount) {
        this.planId = planId;
        this.changedOn = changedOn;
        this.amount = amount;
    }

    public Long getPlanId() {
        return planId;
    }

    public LocalDate getChangedOn() {
        return changedOn;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
