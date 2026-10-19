package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A period of a sheet: a name and a from and to date, both inclusive. */
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

    /** The due-total fixed when the period closed; none while it is open. */
    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    protected Period() {}

    public Period(String sheetId, String name, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.name = name;
        this.from = from;
        this.to = to;
    }

    /** Whether this period shares a day with the dates from and to, inclusive. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !this.from.isAfter(to) && !from.isAfter(this.to);
    }

    /** Whether the date falls in this period, inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Close the period, fixing its due-total. */
    public void close(BigDecimal total) {
        this.closed = true;
        this.closedTotal = total;
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedTotal = null;
    }

    public boolean isClosed() {
        return closed;
    }

    public BigDecimal getClosedTotal() {
        return closedTotal;
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
}
