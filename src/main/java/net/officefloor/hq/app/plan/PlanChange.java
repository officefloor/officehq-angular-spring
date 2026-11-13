package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A change of a plan's amount: the day it was made and the new amount, which the claims it makes from then on
 *  carry. */
@Embeddable
public class PlanChange {

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    protected PlanChange() {
    }

    PlanChange(LocalDate changedOn, BigDecimal amount) {
        this.changedOn = changedOn;
        this.amount = amount;
    }

    public LocalDate getChangedOn() {
        return changedOn;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
