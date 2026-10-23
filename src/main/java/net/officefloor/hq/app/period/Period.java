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

/**
 * A period on a sheet: a name, unique on the sheet ignoring case by its {@link #keyOf key}, and a
 * from and to date, both inclusive. No two periods on a sheet overlap. A closed period keeps the
 * due-total it had when it was closed, and each person's due-total and carried figure for it then, until
 * it is reopened.
 * A soft-closed period fixes them the same way, but claims due by in it can still change; it can be
 * closed (fixing them afresh) or reopened.
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

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** Whether the period is soft-closed: its figures fixed, its claims still open to change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** Whether a steward has locked the period: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    /** Each person's due-total for the period fixed when it was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** Each person's carried figure for the period fixed when it was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> personCarried = new HashMap<>();

    /** Each person's opening balance for the period, the sum of the amounts imported for them, by person id. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /** The last number the period gave a claim opened in it under period numbering; 0 before any. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The most OPEN or PENDING claims due by in the period a person may have; null for no cap. */
    @Column(name = "claim_cap")
    private Integer claimCap;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this(sheetId, name, fromDate, toDate, null);
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate, Integer claimCap) {
        this.sheetId = sheetId;
        this.name = clean(name);
        this.nameKey = keyOf(name);
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.claimCap = claimCap;
    }

    /** The name as it is kept: surrounding spaces removed. */
    public static String clean(String name) {
        return name.trim();
    }

    /** The key a name is unique by on its sheet: kept as cleaned, ignoring case. */
    public static String keyOf(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
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

    /** The most OPEN or PENDING claims due by in the period a person may have; null for no cap. */
    public Integer getClaimCap() {
        return claimCap;
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

    /** The due-total fixed when the period was closed; null while it is open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total fixed when the period was closed, by person id; empty while it is open. */
    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    /** Each person's carried figure fixed when the period was closed, by person id; empty while it is open. */
    public Map<Long, BigDecimal> getPersonCarried() {
        return Map.copyOf(personCarried);
    }

    /** Each person's opening balance for the period, by person id; a person with none is not listed. */
    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Add an amount to the person's opening balance for the period. */
    public void addOpening(long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Close the period, fixing each person's due-total and carried figure for it and its own due-total, their sum. */
    public void close(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried) {
        fixTotals(totals, carried);
        this.closed = true;
        this.softClosed = false;
    }

    /** Soft-close the period, fixing its figures as a close does while its claims stay open to change. */
    public void softClose(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried) {
        fixTotals(totals, carried);
        this.softClosed = true;
    }

    private void fixTotals(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried) {
        this.personTotals.clear();
        this.personTotals.putAll(totals);
        this.personCarried.clear();
        this.personCarried.putAll(carried);
        this.closedTotal = totals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
        this.personCarried.clear();
    }

    /** Whether the date falls inside this period (inclusive). */
    public boolean contains(LocalDate date) {
        return !fromDate.isAfter(date) && !toDate.isBefore(date);
    }

    /** Whether this period shares any day with the inclusive range {@code from} to {@code to}. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !fromDate.isAfter(to) && !toDate.isBefore(from);
    }

    /** Give out the period's next claim number: 1 for the first, then 2, and so on. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
