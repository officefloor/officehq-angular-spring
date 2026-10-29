package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A change of a plan's amount: the sheet's today when it was made and the new amount. */
@Embeddable
public class PlanChange {

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    protected PlanChange() {
    }

    public PlanChange(LocalDate changedOn, BigDecimal amount) {
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
