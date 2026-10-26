package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One change of a plan's amount: the day it was made and the new amount. */
@Embeddable
public class PlanChange {

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    protected PlanChange() {
    }

    PlanChange(LocalDate changedOn, BigDecimal amount) {
        this.changedOn = changedOn;
        this.amount = amount;
    }

    /** The change as the screen lists it: the day, then amount=the new amount. */
    String line() {
        return changedOn + " amount=" + amount.toPlainString();
    }
}
