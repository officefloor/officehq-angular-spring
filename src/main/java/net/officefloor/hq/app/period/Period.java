package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A period on a sheet: a name unique on the sheet and an inclusive from and to date. Once closed, its
 * due-total is fixed as it was when it was closed,
 * until it is reopened.
 */
@Entity
@Table(name = "period")
public class Period {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.name = name;
        this.from = from;
        this.to = to;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The due-total fixed when the period was closed; null while it is open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public void close(BigDecimal total) {
        this.closed = true;
        this.closedTotal = total;
    }

    /** Reopen a closed period, so its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedTotal = null;
    }

    /** Whether a date falls in this period, inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Whether this period shares any day with the dates from to to, inclusive. */
    public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
        return !from.isAfter(otherTo) && !otherFrom.isAfter(to);
    }
}
