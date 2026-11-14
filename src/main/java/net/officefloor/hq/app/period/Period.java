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
import jakarta.persistence.Lob;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** A sheet's period: a name unique on the sheet and the dates it runs from and to, both inclusive. */
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

    /** Whether the period is soft-closed: its figures are fixed but claims dated in it can change. */
    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    /** Each person's due-total for the period fixed when it was closed, by person id; empty while it
     *  is open. A person not in it counts 0.00 for the closed period. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> personTotals = new HashMap<>();

    /** Each person's carried figure for the period fixed when it was closed or soft-closed, by person
     *  id; empty while it is open. A person not in it counts 0.00 for the closed period. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> personCarried = new HashMap<>();

    /** Each person's opening balance for the period, by person id: added to their carried figure for
     *  it. A person not in it has none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    /** The most OPEN and PENDING claims a person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer cap;

    /** Whether a steward has locked the period: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The pools, pot and people as they stood when the period was closed or soft-closed (JSON), each
     *  pool member's cut for it worked out from these; null while it is open. */
    @Lob
    @Column(name = "frozen_pools")
    private String frozenPools;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.name = name;
        this.from = from;
        this.to = to;
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to, Integer cap) {
        this(sheetId, name, from, to);
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

    public Map<Long, BigDecimal> getPersonTotals() {
        return Map.copyOf(personTotals);
    }

    public Map<Long, BigDecimal> getPersonCarried() {
        return Map.copyOf(personCarried);
    }

    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Add an amount to the person's opening balance for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount.setScale(2), BigDecimal::add);
    }

    /** Take an amount back off the person's opening balance for the period; one left at 0.00 or below
     *  is removed. */
    public void takeOpening(Long personId, BigDecimal amount) {
        BigDecimal left = personOpenings.getOrDefault(personId, BigDecimal.ZERO.setScale(2)).subtract(amount);
        if (left.signum() > 0) {
            personOpenings.put(personId, left.setScale(2));
        } else {
            personOpenings.remove(personId);
        }
    }

    /** Close the period, fixing its due-total and each person's due-total and carried figure for it. */
    public void close(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closed = true;
        this.softClosed = false;
        fix(total, personTotals, personCarried);
    }

    /** Soft-close the period, fixing its due-total and each person's due-total and carried figure for
     *  it while claims dated in it can still change. */
    public void softClose(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.softClosed = true;
        fix(total, personTotals, personCarried);
    }

    private void fix(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closedTotal = total;
        this.personTotals.clear();
        this.personTotals.putAll(personTotals);
        this.personCarried.clear();
        this.personCarried.putAll(personCarried);
    }

    /** Reopen the period: its due-total and carried figures are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.personTotals.clear();
        this.personCarried.clear();
        this.frozenPools = null;
    }

    public String getFrozenPools() {
        return frozenPools;
    }

    /** Fix the pools, pot and people as they stand (JSON), the period's cuts worked out from them. */
    public void freezePools(String frozenPools) {
        this.frozenPools = frozenPools;
    }

    /** Whether the date falls in the period (inclusive). */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Whether the period shares a day with the dates from and to (inclusive). */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !this.from.isAfter(to) && !from.isAfter(this.to);
    }
}
