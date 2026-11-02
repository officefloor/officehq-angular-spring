package net.officefloor.hq.app.period;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** A period on a sheet: a named run of days from its from-date to its to-date, both inclusive. A
 *  claim belongs to the period whose days contain its date. A closed period keeps the due-total it
 *  had when it was closed, and no claim dated inside it can be changed. A soft-closed period keeps
 *  its due-total as a closed one does, but claims dated inside it can still be added and changed. */
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

    /** Each person's due-total for the period, by the person's id, fixed when it was closed; empty
     *  while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** The last number given to a claim in the period under period numbering; only ever moved on by
     *  {@link PeriodRepository#advanceClaimNumber}. */
    @Column(name = "last_claim_number", insertable = false, updatable = false)
    private int lastClaimNumber;

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

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total fixed when the period was closed, by the person's id; empty while it
     *  is open. */
    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    /** Close the period, fixing each person's due-total for it at what it is now, and its own
     *  due-total at their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        this.closed = true;
        this.softClosed = false;
        fix(personTotals);
    }

    /** Soft-close the period, fixing its due-totals as {@link #close} does while leaving claims dated
     *  inside it open to change. */
    public void softClose(Map<Long, BigDecimal> personTotals) {
        this.softClosed = true;
        fix(personTotals);
    }

    private void fix(Map<Long, BigDecimal> personTotals) {
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
    }
}
