package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A line of a claim's binding log: on the sheet's today then, it was bound by a statement to a figure
 * (none when it had none), or left unbound.
 */
@Embeddable
public class ClaimBinding {

    @Column(name = "on_date", nullable = false)
    private LocalDate date;

    @Column(name = "stated", nullable = false)
    private boolean stated;

    @Column(name = "due", precision = 14, scale = 2)
    private BigDecimal due;

    protected ClaimBinding() {}

    public ClaimBinding(LocalDate date, boolean stated, BigDecimal due) {
        this.date = date;
        this.stated = stated;
        this.due = due;
    }

    public LocalDate getDate() { return date; }

    public boolean isStated() { return stated; }

    public BigDecimal getDue() { return due; }
}
