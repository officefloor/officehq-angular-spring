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
 *  claim belongs to the period whose days contain its due-by. A closed period keeps the due-total it
 *  had when it was closed, and no claim due by a day inside it can be changed. A soft-closed period
 *  keeps its due-total as a closed one does, but claims due inside it can still be added and changed. */
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

    /** Whether a steward has locked the period: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The most open or pending claims a person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer claimCap;

    /** Each person's due-total for the period, by the person's id, fixed when it was closed; empty
     *  while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** Each person's carried figure for the period, by the person's id, fixed when it was closed or
     *  soft-closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> personCarried = new HashMap<>();

    /** Each pool member's cut for the period, by "pool-id:member-key", fixed when it was closed or
     *  soft-closed (their cut as it stood at that moment); empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "cut_key")
    @Column(name = "cut", nullable = false)
    private Map<String, BigDecimal> poolCuts = new HashMap<>();

    /** Each person's opening balance for the period, by the person's id: what pasted opening
     *  balances have added to their carried figure for it. Kept while the period is open or closed. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", nullable = false)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /** The last number given to a claim in the period under period numbering; only ever moved on by
     *  {@link PeriodRepository#advanceClaimNumber}. */
    @Column(name = "last_claim_number", insertable = false, updatable = false)
    private int lastClaimNumber;

    protected Period() {
    }

    /** A period as a revision in its sheet's history has it, detached: never saved. */
    static Period revised(String sheetId, Long id, String name, LocalDate fromDate, LocalDate toDate,
            boolean closed, boolean softClosed, BigDecimal closedTotal, Map<Long, BigDecimal> personTotals,
            Map<Long, BigDecimal> personCarried, Map<Long, BigDecimal> personOpenings, Integer claimCap,
            boolean locked, Map<String, BigDecimal> poolCuts) {
        Period period = new Period(sheetId, name, fromDate, toDate, claimCap);
        period.id = id;
        period.closed = closed;
        period.softClosed = softClosed;
        period.locked = locked;
        period.closedTotal = closedTotal;
        period.personTotals.putAll(personTotals);
        period.personCarried.putAll(personCarried);
        period.personOpenings.putAll(personOpenings);
        period.poolCuts.putAll(poolCuts);
        return period;
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this(sheetId, name, fromDate, toDate, null);
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate, Integer claimCap) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    /** The most open or pending claims a person may have in the period; null for no cap. */
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

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total fixed when the period was closed, by the person's id; empty while it
     *  is open. */
    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    /** Each pool member's cut fixed when the period was closed or soft-closed, by "pool-id:member-key";
     *  empty while it is open. */
    public Map<String, BigDecimal> getPoolCuts() {
        return poolCuts;
    }

    /** Each person's carried figure fixed when the period was closed or soft-closed, by the person's
     *  id; empty while it is open. */
    public Map<Long, BigDecimal> getPersonCarried() {
        return Map.copyOf(personCarried);
    }

    /** Each person's opening balance for the period, by the person's id; a person missing has 0.00. */
    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Add an amount to a person's opening balance for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Drop the opening balances that have come to zero: a person missing has 0.00. */
    public void dropZeroOpenings() {
        personOpenings.values().removeIf(amount -> amount.signum() == 0);
    }

    /** Close the period, fixing each person's due-total and carried figure for it at what they are
     *  now, each pool member's cut for it at what it is now, and its own due-total at the sum of the
     *  due-totals. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried,
            Map<String, BigDecimal> poolCuts) {
        this.closed = true;
        this.softClosed = false;
        fix(personTotals, personCarried, poolCuts);
    }

    /** Soft-close the period, fixing its due-totals as {@link #close} does while leaving claims dated
     *  inside it open to change. */
    public void softClose(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried,
            Map<String, BigDecimal> poolCuts) {
        this.softClosed = true;
        fix(personTotals, personCarried, poolCuts);
    }

    private void fix(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried,
            Map<String, BigDecimal> poolCuts) {
        this.poolCuts.clear();
        this.poolCuts.putAll(poolCuts);
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.personCarried.clear();
        this.personCarried.putAll(personCarried);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Fold one person's fixed due-total, carried figure and opening balance for the period into another's (the one
     *  was merged into the other); the period's own due-total stays as it is. */
    public void mergePersonTotal(Long fromId, Long intoId) {
        BigDecimal from = personTotals.remove(fromId);
        if (from != null) {
            personTotals.merge(intoId, from, BigDecimal::add);
        }
        BigDecimal carried = personCarried.remove(fromId);
        if (carried != null) {
            personCarried.merge(intoId, carried, BigDecimal::add);
        }
        BigDecimal opening = personOpenings.remove(fromId);
        if (opening != null) {
            personOpenings.merge(intoId, opening, BigDecimal::add);
        }
    }

    /** Drop one person's fixed due-total, carried figure and opening balance for the period (they left the sheet);
     *  the period's own due-total stays as it is. */
    public void dropPersonTotal(Long personId) {
        personTotals.remove(personId);
        personCarried.remove(personId);
        personOpenings.remove(personId);
    }

    /** Reopen the period: its due-totals and carried figures are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
        this.personCarried.clear();
        this.poolCuts.clear();
    }
}
