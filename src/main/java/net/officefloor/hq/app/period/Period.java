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
import org.hibernate.annotations.Formula;
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

    /** Whether the period is soft-closed: its figures are fixed as for a close, but claims dated inside it can still
     *  be added and changed. Never set together with closed. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** Whether a steward has locked the period: while locked it cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The period's due-total fixed when it was closed or soft-closed; none while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, by person id, fixed when it was closed or soft-closed; empty while it
     *  is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due_total", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    /** Each person's carried figure for the period, by person id, fixed when it was closed or soft-closed; empty
     *  while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> closedPersonCarried = new HashMap<>();

    /** Each pool member's cut fixed when the period was closed or soft-closed, as it stood at that moment, by the
     *  pool's id and the member's key within it ({@code <pool id>/<member key>}); empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "cut_key")
    @Column(name = "cut", nullable = false, precision = 14, scale = 2)
    private Map<String, BigDecimal> closedPoolCuts = new HashMap<>();

    /** Each person's opening balance for the period, by person id: the sum of the amounts pasted for them, added to
     *  their carried figure for it. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /** The most OPEN or PENDING claims a person may have in the period; none means no cap. */
    @Column(name = "claim_cap")
    private Integer claimCap;

    /** How many claims of the sheet belong to the period (their due-by falls inside it), VOID and DELETED ones left
     *  out; read from the store as the period is loaded. */
    @Formula("(SELECT COUNT(*) FROM claim c WHERE c.sheet_id = sheet_id AND c.due_by >= from_date"
            + " AND c.due_by <= to_date AND c.status NOT IN ('VOID', 'DELETED'))")
    private long claimsCount;

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

    public Integer getClaimCap() {
        return claimCap;
    }

    public long getClaimsCount() {
        return claimsCount;
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

    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return Map.copyOf(closedPersonTotals);
    }

    public Map<Long, BigDecimal> getClosedPersonCarried() {
        return Map.copyOf(closedPersonCarried);
    }

    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    public Map<String, BigDecimal> getClosedPoolCuts() {
        return Map.copyOf(closedPoolCuts);
    }

    /** Whether the period's figures are fixed: closed or soft-closed. */
    public boolean isFixed() {
        return closed || softClosed;
    }

    /** Fix each pool member's cut for the period as it stands now; called as the period is closed or soft-closed. */
    public void fixPoolCuts(Map<String, BigDecimal> poolCuts) {
        this.closedPoolCuts.clear();
        this.closedPoolCuts.putAll(poolCuts);
    }

    /** A person's opening balance for the period; 0.00 when none was pasted. */
    public BigDecimal openingOf(Long personId) {
        return personOpenings.getOrDefault(personId, BigDecimal.ZERO.setScale(2));
    }

    /** Add an amount to a person's opening balance for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Take an amount back off a person's opening balance for the period; a balance left at 0.00 is removed. */
    public void takeBackOpening(Long personId, BigDecimal amount) {
        BigDecimal left = openingOf(personId).subtract(amount);
        if (left.signum() == 0) {
            personOpenings.remove(personId);
        } else {
            personOpenings.put(personId, left);
        }
    }

    /** Close the period, fixing each person's due-total and carried figure for it; its own due-total is the sum of
     *  theirs. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(personTotals, personCarried);
        this.closed = true;
    }

    /** Soft-close the period, fixing each person's due-total for it as a close does, but leaving its claims open to
     *  change. */
    public void softClose(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(personTotals, personCarried);
        this.softClosed = true;
    }

    private void fix(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closed = false;
        this.softClosed = false;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedPersonCarried.clear();
        this.closedPersonCarried.putAll(personCarried);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Fold a person merged into another into the other's fixed figures for the period: the merged person's
     *  due-total and carried figure are added to the other's. The period's own due-total is unchanged. */
    public void mergePerson(Long fromId, Long intoId) {
        BigDecimal total = closedPersonTotals.remove(fromId);
        if (total != null) {
            closedPersonTotals.merge(intoId, total, BigDecimal::add);
        }
        BigDecimal carried = closedPersonCarried.remove(fromId);
        if (carried != null) {
            closedPersonCarried.merge(intoId, carried, BigDecimal::add);
        }
    }

    /** Reopen the period: its due-total and each person's figures for it are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
        this.closedPersonCarried.clear();
        this.closedPoolCuts.clear();
    }
}
