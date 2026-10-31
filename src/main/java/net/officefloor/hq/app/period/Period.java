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

/** A period of a sheet: a name unique on the sheet, from and to dates inclusive. */
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

    /** Whether the period is closed: no claim dated inside it can then be added, edited or moved. */
    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** The period's due-total fixed when it was closed; none while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, by person id, fixed when it was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due_total", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    /** Whether a date falls inside this period. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    /** Whether this period shares a day with the period from {@code from} to {@code to}. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !fromDate.isAfter(to) && !from.isAfter(toDate);
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

    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return Map.copyOf(closedPersonTotals);
    }

    /** Close the period, fixing each person's due-total for it; its own is their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        this.closed = true;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
    }
}
