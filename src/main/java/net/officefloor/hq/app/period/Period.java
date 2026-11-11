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

/** A period of a sheet: a name unique on the sheet, and the from and to dates it covers, both inclusive. */
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

    /** Whether the period is soft-closed: its figures are fixed, yet claims dated in it can still change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, by person id, fixed when it was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", precision = 14, scale = 2)
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

    public boolean isSoftClosed() {
        return softClosed;
    }

    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    /** Close the period, fixing each person's due-total for it and its own, their sum. */
    public void close(Map<Long, BigDecimal> totals) {
        this.closed = true;
        this.softClosed = false;
        fix(totals);
    }

    /** Soft-close the period, fixing its figures as a close does while its claims can still change. */
    public void softClose(Map<Long, BigDecimal> totals) {
        this.softClosed = true;
        fix(totals);
    }

    private void fix(Map<Long, BigDecimal> totals) {
        this.personTotals.clear();
        this.personTotals.putAll(totals);
        this.closedTotal = totals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Fold one person's fixed due-total into another's, keeping the period's own (a merge of the two). */
    public void mergePersonTotal(Long fromId, Long intoId) {
        BigDecimal from = personTotals.remove(fromId);
        if (from != null) {
            personTotals.merge(intoId, from, BigDecimal::add);
        }
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
    }

    /** Whether the date falls in the period, both ends inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    /** Whether the period shares a day with the dates from and to, both inclusive. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !fromDate.isAfter(to) && !from.isAfter(toDate);
    }
}
