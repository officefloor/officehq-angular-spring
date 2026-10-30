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
 * A period on a sheet: a name unique on the sheet, and a from and to date, both inclusive. Once closed or
 * soft-closed, its due-total is the one fixed then; claims due by a date inside a soft-closed one can still change.
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

    /** Whether the period is soft-closed: its figures are fixed, but claims due by a date inside it can still change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** Whether a steward has locked the period: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer cap;

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Per person id, the person's due-total fixed when the period was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** Per person id, the person's carried figure fixed when the period was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> personCarried = new HashMap<>();

    /** Per person id, the opening balances pasted for the person in the period, added up: part of their carried figure. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /**
     * Per pool member (see {@code Pools.key}), the member's cut fixed when the period was closed, as the pools stood
     * then; a member with no cut then has none. Empty while it is open.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "member_key")
    @Column(name = "cut", nullable = false, precision = 14, scale = 2)
    private Map<String, BigDecimal> poolCuts = new HashMap<>();

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate, Integer cap) {
        this(sheetId, name, fromDate, toDate);
        this.cap = cap;
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

    /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
    public Integer getCap() {
        return cap;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isSoftClosed() {
        return softClosed;
    }

    public boolean isLocked() {
        return locked;
    }

    /** Lock or unlock the period. */
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** Whether the period's figures are fixed: it is closed or soft-closed. */
    public boolean isFixed() {
        return closed || softClosed;
    }

    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    public Map<Long, BigDecimal> getPersonCarried() {
        return Map.copyOf(personCarried);
    }

    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    public Map<String, BigDecimal> getPoolCuts() {
        return Map.copyOf(poolCuts);
    }

    /** Fix the pool members' cuts for the period, as the pools stand when it is closed or soft-closed. */
    public void fixPoolCuts(Map<String, BigDecimal> cuts) {
        this.poolCuts.clear();
        this.poolCuts.putAll(cuts);
    }

    /** Add an opening balance to the person's carried figure for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Close the period, fixing each person's due-total for it and its own, their sum. */
    public void close(Map<Long, BigDecimal> personTotals) {
        close(personTotals, Map.of(), false);
    }

    /**
     * Close the period, or soft-close it, fixing each person's due-total and carried figure for it and its own
     * due-total, their sum. A soft-closed period still takes changes to the claims due by a date inside it.
     */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried, boolean soft) {
        this.closed = !soft;
        this.softClosed = soft;
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.personCarried.clear();
        this.personCarried.putAll(personCarried);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Reopen the period: its due-total, carried figures and pool cuts are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
        this.personCarried.clear();
        this.poolCuts.clear();
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
