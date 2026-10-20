package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of a claim's binding log: whether a statement bound it then, to what figure (none when n/a or
 * unbound), and the sheet's today then. */
@Embeddable
public class BindingLogLine {

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "stated", nullable = false)
    private boolean stated;

    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    protected BindingLogLine() {}

    public BindingLogLine(LocalDate on, boolean stated, BigDecimal statedDue) {
        this.on = on;
        this.stated = stated;
        this.statedDue = statedDue;
    }

    public LocalDate getOn() {
        return on;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }
}
