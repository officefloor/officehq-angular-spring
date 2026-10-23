package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement still standing for a person, as read from their statements: its date and due-total (null: n/a). */
@Embeddable
public class StatementLine {

    @Column(name = "taken_on", nullable = false, insertable = false, updatable = false)
    private LocalDate takenOn;

    @Column(name = "total", precision = 14, scale = 2, insertable = false, updatable = false)
    private BigDecimal total;

    protected StatementLine() {
    }

    /** The date the statement was taken as of. */
    public LocalDate getTakenOn() {
        return takenOn;
    }

    /** Its due-total; null when n/a. */
    public BigDecimal getTotal() {
        return total;
    }
}
