package net.officefloor.hq.app.period;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * A period on a sheet: a name unique on the sheet, and a from and to date, both inclusive. Once closed,
 * its due-total is the one fixed when it was closed.
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

    /** The due-total fixed when the period was closed; null while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Per person id, the person's due-total fixed when the period was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

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

    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    /** Close the period, fixing each person's due-total for it and its own, their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        this.closed = true;
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedTotal = null;
        this.personTotals.clear();
    }

    /** Whether the date falls in the period, its from and to date included. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    /** Whether the period shares a date with the one from {@code from} to {@code to}. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !from.isAfter(toDate) && !to.isBefore(fromDate);
    }
}
