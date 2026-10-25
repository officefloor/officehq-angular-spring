package net.officefloor.hq.app.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A change of a plan's amount: the sheet's today it was changed on and the new amount. */
@Embeddable
public class PlanChange {

    @Column(name = "changed_on", nullable = false)
    private LocalDate changedOn;

    @Column(nullable = false)
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

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanChange other && Objects.equals(changedOn, other.changedOn)
                && Objects.equals(amount, other.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(changedOn, amount);
    }
}
