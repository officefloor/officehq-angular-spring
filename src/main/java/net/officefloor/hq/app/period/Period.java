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
import java.util.LinkedHashMap;
import java.util.Map;

/** One of the sheet's periods: a name unique on the sheet and its from and to dates, inclusive;
 *  once closed, its due-total and each person's due-total for it are fixed at the figures they had then. */
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due_total", nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new LinkedHashMap<>();

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

    /** The due-total fixed when the period was closed; null while open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total for the period fixed when it was closed, by person id; empty while open. */
    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return closedPersonTotals;
    }

    /** Close the period, fixing each person's due-total for it at {@code personTotals} (by person
     *  id) and its due-total at their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        this.closed = true;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period, its due-totals live again. */
    public void reopen() {
        this.closed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
    }

    /** Whether the period's dates contain {@code date}. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Whether the period shares a date with the one from {@code otherFrom} to {@code otherTo}. */
    public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
        return !from.isAfter(otherTo) && !otherFrom.isAfter(to);
    }
}
