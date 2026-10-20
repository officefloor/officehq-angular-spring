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

    /** Whether the period is soft-closed: its due-totals are fixed as for a close, but claims dated in it can
     * still change. Only while closed. */
    @Column(name = "soft", nullable = false)
    private boolean soft;

    /** The due-total fixed when the period closed; none while it is open. */
    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, by person id, fixed when it closed; none while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

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

    /** Close the period, soft or not, fixing each person's due-total for it, by person id, and its own: their sum. */
    public void close(Map<Long, BigDecimal> personTotals, boolean soft) {
        this.closed = true;
        this.soft = soft;
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.soft = false;
        this.closedTotal = null;
        this.personTotals.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Whether the period is soft-closed: its figures fixed, its claims still open to change. */
    public boolean isSoftClosed() {
        return closed && soft;
    }

    /** Whether the period is closed and not soft: no claim dated in it may change. */
    public boolean isHardClosed() {
        return closed && !soft;
    }

    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public Map<Long, BigDecimal> getPersonTotals() {
        return personTotals;
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
