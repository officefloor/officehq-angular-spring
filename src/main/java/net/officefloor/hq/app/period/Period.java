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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer cap;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** Whether the period is soft-closed: its figures are fixed, yet claims dated in it can still change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** Whether a steward has locked the period: it cannot be reopened while locked. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    @Column(name = "closed_total", precision = 14, scale = 2)
    private BigDecimal closedTotal;

    /** Each person's due-total for the period, by person id, fixed when it was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", precision = 14, scale = 2)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** Each person's carried figure for the period, by person id, fixed when it was closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", precision = 14, scale = 2)
    private Map<Long, BigDecimal> personCarried = new HashMap<>();

    /** Each person's opening balance for the period, by person id: added to their carried figure for it. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", precision = 14, scale = 2)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /** Each pool member's cut for the period, fixed when it was closed or soft-closed; empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    private List<PeriodPoolCut> poolCuts = new ArrayList<>();

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

    public void setLocked(boolean locked) {
        this.locked = locked;
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

    public List<PeriodPoolCut> getPoolCuts() {
        return List.copyOf(poolCuts);
    }

    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Add the amount to the person's opening balance for the period; a closed period's fixed figures stay as fixed. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Take the amount back off the person's opening balance for the period, dropping it once it comes to zero. */
    public void takeOpening(Long personId, BigDecimal amount) {
        BigDecimal left = personOpenings.getOrDefault(personId, BigDecimal.ZERO).subtract(amount);
        if (left.signum() == 0) {
            personOpenings.remove(personId);
        } else {
            personOpenings.put(personId, left);
        }
    }

    /**
     * Close the period, fixing each person's due-total and carried figure for it, its own due-total, their sum, and each
     * pool member's cut.
     */
    public void close(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried, List<PeriodPoolCut> cuts) {
        this.closed = true;
        this.softClosed = false;
        fix(totals, carried, cuts);
    }

    /** Soft-close the period, fixing its figures as a close does while its claims can still change. */
    public void softClose(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried, List<PeriodPoolCut> cuts) {
        this.softClosed = true;
        fix(totals, carried, cuts);
    }

    private void fix(Map<Long, BigDecimal> totals, Map<Long, BigDecimal> carried, List<PeriodPoolCut> cuts) {
        this.poolCuts.clear();
        this.poolCuts.addAll(cuts);
        this.personTotals.clear();
        this.personTotals.putAll(totals);
        this.personCarried.clear();
        this.personCarried.putAll(carried);
        this.closedTotal = totals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Fold one person's fixed due-total, carried figure and opening balance into another's, keeping the period's own (a merge of the two). */
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
        String fromKey = "person:" + fromId;
        String intoKey = "person:" + intoId;
        if (poolCuts.stream().anyMatch(c -> c.getMember().equals(fromKey))) {
            Map<PeriodPoolCut, BigDecimal> merged = new LinkedHashMap<>();
            for (PeriodPoolCut c : poolCuts) {
                PeriodPoolCut key = new PeriodPoolCut(c.getPoolId(),
                        c.getMember().equals(fromKey) ? intoKey : c.getMember(), c.getCut());
                merged.merge(key, c.getCut(), BigDecimal::add);
            }
            poolCuts.clear();
            merged.forEach((k, cut) -> poolCuts.add(new PeriodPoolCut(k.getPoolId(), k.getMember(), cut)));
        }
    }

    /** Reopen the period: its due-total and its pool members' cuts are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
        this.personCarried.clear();
        this.poolCuts.clear();
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
