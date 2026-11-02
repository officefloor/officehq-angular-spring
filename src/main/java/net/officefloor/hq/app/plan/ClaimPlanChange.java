package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A change of a plan's amount: the day it was changed and the new amount, taken by claims generated from then on. */
@Entity
@Table(name = "claim_plan_change")
public class ClaimPlanChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    protected ClaimPlanChange() {}

    public ClaimPlanChange(String sheetId, Long planId, LocalDate changedOn, BigDecimal amount) {
        this.sheetId = sheetId;
        this.planId = planId;
        this.changedOn = changedOn;
        this.amount = amount;
    }

    public Long getId() { return id; }

    public Long getPlanId() { return planId; }

    public LocalDate getChangedOn() { return changedOn; }

    public BigDecimal getAmount() { return amount; }
}
