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
 * A period of the sheet: a name unique on the sheet, from and to a date, both inclusive. Once
 * closed, its due-total is fixed at what it was when it was closed; reopened, it is live again.
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
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** The due-total fixed when the period was closed; none while it is open. */
    @Column(name = "closed_due_total", precision = 14, scale = 2)
    private BigDecimal closedDueTotal;

    protected Period() {}

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public LocalDate getFromDate() { return fromDate; }

    public LocalDate getToDate() { return toDate; }

    public boolean isClosed() { return closed; }

    public BigDecimal getClosedDueTotal() { return closedDueTotal; }

    /** Close it, fixing its due-total. */
    public void close(BigDecimal dueTotal) {
        this.closed = true;
        this.closedDueTotal = dueTotal;
    }

    /** Reopen it: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedDueTotal = null;
    }

    /** Whether the date falls in it, both ends inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    /** Whether it shares a date with the other dates, both inclusive. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !fromDate.isAfter(to) && !from.isAfter(toDate);
    }
}
