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
import java.util.HashMap;
import java.util.Map;

/** A named period on the sheet, from one date to another, both inclusive. A claim belongs to the
 *  period whose dates contain its date, or to none. A closed period keeps the due-total it had when
 *  closed, and its claims can no longer change, until a steward reopens it. A soft-closed period keeps
 *  its figures fixed the same way, but its claims can still change. */
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

    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, fixed when it was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Soft-closed: its figures are fixed, but its claims can still change. */
    public boolean isSoftClosed() {
        return softClosed;
    }

    /** The due-total fixed when it was closed or soft-closed; null while open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total fixed when it was closed, by person id; empty while open. */
    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return closedPersonTotals;
    }

    /** Close the period, fixing each person's due-total for it, and its own as their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        fix(personTotals);
        this.closed = true;
        this.softClosed = false;
    }

    /** Soft-close the period, fixing its figures as a close does while its claims can still change. */
    public void softClose(Map<Long, BigDecimal> personTotals) {
        fix(personTotals);
        this.softClosed = true;
    }

    private void fix(Map<Long, BigDecimal> personTotals) {
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
    }

    /** Whether a date falls in this period. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    /** Whether this period shares a day with one from one date to another. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !fromDate.isAfter(to) && !from.isAfter(toDate);
    }
}
