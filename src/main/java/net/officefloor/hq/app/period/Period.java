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
import java.util.Locale;
import java.util.Map;

/** One of the sheet's periods: a name unique on the sheet and a from and to date, both inclusive. */
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

    /** The name as uniqueness compares it: trimmed and lower-cased. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** Whether the period is soft-closed: its figures are fixed, but its claims can still change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    /** Each person's due-total fixed when the period was closed, by person id; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    /** Each person's carried figure fixed when the period was closed or soft-closed, by person id; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> closedPersonCarried = new HashMap<>();

    /** How many OPEN or PENDING claims a person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer cap;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.name = name.trim();
        this.nameKey = keyOf(name);
        this.from = from;
        this.to = to;
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to, Integer cap) {
        this(sheetId, name, from, to);
        this.cap = cap;
    }

    /** The name as uniqueness compares it. */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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

    public String getNameKey() {
        return nameKey;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public Integer getCap() {
        return cap;
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

    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return closedPersonTotals;
    }

    public Map<Long, BigDecimal> getClosedPersonCarried() {
        return closedPersonCarried;
    }

    /** Close the period, fixing its due-total and each person's due-total and carried figure for it, by person id. */
    public void close(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(total, personTotals, personCarried);
        this.closed = true;
    }

    /** Soft-close the period, fixing its figures as a close does while its claims can still change. */
    public void softClose(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(total, personTotals, personCarried);
        this.softClosed = true;
    }

    private void fix(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = total;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedPersonCarried.clear();
        this.closedPersonCarried.putAll(personCarried);
    }

    /** Reopen the period: its due-total and carried figures are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
        this.closedPersonCarried.clear();
    }

    /** Whether the date falls within this period, both ends inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Whether this period shares a day with the dates from and to, both inclusive. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !this.from.isAfter(to) && !from.isAfter(this.to);
    }
}
