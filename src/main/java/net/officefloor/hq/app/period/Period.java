package net.officefloor.hq.app.period;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import net.officefloor.hq.app.history.HistoryListener;

/** One of the sheet's periods: a name unique on the sheet and its from and to dates, inclusive;
 *  once closed (or soft-closed), its due-total and each person's due-total and carried figure for it
 *  are fixed at the figures they had then; a soft-closed period still takes changes to the claims due inside it. */
@Entity
@EntityListeners(HistoryListener.class)
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

    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "claim_cap")
    private Integer cap;

    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due_total", nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new LinkedHashMap<>();

    @Column(name = "carried_fixed", nullable = false)
    private boolean carriedFixed;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> closedPersonCarried = new LinkedHashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new LinkedHashMap<>();

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

    /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
    public Integer getCap() {
        return cap;
    }

    public void setCap(Integer cap) {
        this.cap = cap;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isSoftClosed() {
        return softClosed;
    }

    /** Whether a steward has locked the period: a locked one cannot be reopened. */
    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** The due-total fixed when the period was closed; null while open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total for the period fixed when it was closed, by person id; empty while open. */
    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return closedPersonTotals;
    }

    /** Whether each person's carried figure for the period was fixed when it was closed (false
     *  while open, and for one closed before carried figures were fixed: they show live). */
    public boolean isCarriedFixed() {
        return carriedFixed;
    }

    /** Each person's carried figure for the period fixed when it was closed, by person id; empty while open. */
    public Map<Long, BigDecimal> getClosedPersonCarried() {
        return closedPersonCarried;
    }

    /** Each person's opening balance for the period, by person id: added to the carried figure
     *  they show for it while it is open (a closed one keeps the figure it fixed). */
    public Map<Long, BigDecimal> getOpenings() {
        return openings;
    }

    /** Add {@code amount} to the person's opening balance for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        openings.merge(personId, amount, BigDecimal::add);
    }

    /** Close the period, fixing each person's due-total for it at {@code personTotals} and their
     *  carried figure at {@code personCarried} (null: none fixed), by person id, and its due-total
     *  at their due-totals' sum. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closed = true;
        this.softClosed = false;
        fix(personTotals, personCarried);
    }

    /** Soft-close the period: its figures are fixed as by {@link #close}, but claims dated inside
     *  it can still be changed. */
    public void softClose(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.softClosed = true;
        this.closed = false;
        fix(personTotals, personCarried);
    }

    private void fix(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        this.closedPersonCarried.clear();
        this.carriedFixed = personCarried != null;
        if (personCarried != null) {
            this.closedPersonCarried.putAll(personCarried);
        }
    }

    /** Reopen the period, its due-totals live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
        this.carriedFixed = false;
        this.closedPersonCarried.clear();
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
