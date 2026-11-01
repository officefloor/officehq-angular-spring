package net.officefloor.hq.app.period;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * A period of the sheet: a name unique on the sheet, from and to a date, both inclusive. Once
 * closed, its due-total and each person's due-total for it are fixed at what they were when it was
 * closed; reopened, they are live again. Soft-closed, its figures are fixed the same way, but claims
 * dated inside it can still change.
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

    /** Whether it is soft-closed: its figures fixed, its claims still free to change. Never with closed. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** The due-total fixed when the period was closed or soft-closed; none while it is open. */
    @Column(name = "closed_due_total", precision = 14, scale = 2)
    private BigDecimal closedDueTotal;

    /** Each person's due-total for it fixed when it was closed, by person id; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_due", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due_total", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> closedPersonDues = new TreeMap<>();

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

    public boolean isSoftClosed() { return softClosed; }

    public BigDecimal getClosedDueTotal() { return closedDueTotal; }

    public Map<Long, BigDecimal> getClosedPersonDues() { return closedPersonDues; }

    /** Close it, fixing each person's due-total for it and its own, the sum of theirs. */
    public void close(Map<Long, BigDecimal> personDues) {
        fix(personDues);
        this.closed = true;
        this.softClosed = false;
    }

    /** Soft-close it, fixing its figures as a close does while its claims can still change. */
    public void softClose(Map<Long, BigDecimal> personDues) {
        fix(personDues);
        this.softClosed = true;
    }

    private void fix(Map<Long, BigDecimal> personDues) {
        this.closedPersonDues.clear();
        this.closedPersonDues.putAll(personDues);
        this.closedDueTotal = personDues.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen it: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedDueTotal = null;
        this.closedPersonDues.clear();
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
