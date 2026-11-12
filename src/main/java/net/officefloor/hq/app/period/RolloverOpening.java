package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** An opening balance a rollover added: the period it went to, the person's id, and the amount (two decimals). */
@Embeddable
public class RolloverOpening {

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    protected RolloverOpening() {
    }

    public RolloverOpening(Long periodId, Long personId, BigDecimal amount) {
        this.periodId = periodId;
        this.personId = personId;
        this.amount = amount;
    }

    public Long getPeriodId() {
        return periodId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
