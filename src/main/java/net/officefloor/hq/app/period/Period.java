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

/**
 * A period on a sheet: a name and a from and to date, both inclusive. A closed period keeps the
 * due-total it had when it was closed, each person's due-total and carried figure for it and each pool member's
 * cut at that moment; so does a
 * soft-closed one, whose claims can still change.
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

    /** Soft-closed: the figures are fixed as for a closed period, but its claims can still change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** Locked by a steward: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "closed_due")
    private BigDecimal closedDue;

    /** Each person's due-total fixed when the period was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_due", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due", nullable = false)
    private Map<Long, BigDecimal> closedDues = new HashMap<>();

    /** Each person's carried figure fixed when the period was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> closedCarried = new HashMap<>();

    /** Each person's opening balance for the period, added to their carried figure into it, by person id. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    /**
     * Each pool member's cut fixed when the period was closed, by the member's place on the sheet (see
     * {@code PoolService}); empty while open.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "member_key")
    @Column(name = "cut", nullable = false)
    private Map<String, String> closedCuts = new HashMap<>();

    /** The last number given to a claim opened in this period under numbering per period. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** At most this many OPEN or PENDING claims per person may be in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer claimCap;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate, Integer claimCap) {
        this(sheetId, name, fromDate, toDate);
        this.claimCap = claimCap;
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

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public BigDecimal getClosedDue() {
        return closedDue;
    }

    public Map<Long, BigDecimal> getClosedDues() {
        return closedDues;
    }

    public Map<Long, BigDecimal> getClosedCarried() {
        return closedCarried;
    }

    public Map<Long, BigDecimal> getOpenings() {
        return openings;
    }

    public Map<String, String> getClosedCuts() {
        return closedCuts;
    }

    /** Add an amount to a person's opening balance for the period. */
    public void addOpening(long personId, BigDecimal amount) {
        openings.merge(personId, amount, BigDecimal::add);
    }

    /** Give out the next claim number within this period. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }

    /**
     * Close the period, fixing each person's due-total and carried figure for it, and each pool
     * member's cut, at this moment; its own due-total is the sum of their due-totals.
     */
    public void close(Map<Long, BigDecimal> dues, Map<Long, BigDecimal> carried, Map<String, String> cuts) {
        fix(dues, carried, cuts);
        this.closed = true;
        this.softClosed = false;
    }

    /**
     * Soft-close the period, fixing its figures as {@link #close} does while its claims can still
     * change.
     */
    public void softClose(Map<Long, BigDecimal> dues, Map<Long, BigDecimal> carried, Map<String, String> cuts) {
        fix(dues, carried, cuts);
        this.softClosed = true;
    }

    private void fix(Map<Long, BigDecimal> dues, Map<Long, BigDecimal> carried, Map<String, String> cuts) {
        this.closedCuts.clear();
        this.closedCuts.putAll(cuts);
        this.closedDues.clear();
        this.closedDues.putAll(dues);
        this.closedCarried.clear();
        this.closedCarried.putAll(carried);
        this.closedDue = dues.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total and its members' cuts are live again. */
    public void reopen() {
        this.closedCuts.clear();
        this.closed = false;
        this.softClosed = false;
        this.closedDue = null;
        this.closedDues.clear();
        this.closedCarried.clear();
    }
}
