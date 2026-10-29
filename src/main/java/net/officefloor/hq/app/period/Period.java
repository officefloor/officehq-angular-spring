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
 * A period of a sheet: a name unique on the sheet and a from and to date, inclusive. A closed period keeps the
 * due-total it had when it was closed, and each person's due-total and carried figure for it then. A soft-closed period keeps them
 * the same way, but claims due by a date in it can still change.
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
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "claim_cap")
    private Integer cap;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "soft_closed", nullable = false)
    private boolean softClosed;

    @Column(name = "closed_total")
    private BigDecimal closedTotal;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", nullable = false)
    private Map<Long, BigDecimal> closedPersonCarried = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to) {
        this(sheetId, name, from, to, null);
    }

    public Period(String sheetId, String name, LocalDate from, LocalDate to, Integer cap) {
        this.sheetId = sheetId;
        this.name = name;
        this.from = from;
        this.to = to;
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

    /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
    public BigDecimal getClosedTotal() {
        return closedTotal;
    }

    /** Each person's due-total fixed when the period was closed, by person id; empty while it is open. */
    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return Map.copyOf(closedPersonTotals);
    }

    /** Each person's carried figure fixed when the period was closed, by person id; empty while it is open. */
    public Map<Long, BigDecimal> getClosedPersonCarried() {
        return Map.copyOf(closedPersonCarried);
    }

    /** Each person's opening balance for the period, by person id; a person with none has no entry. */
    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Adds the amount to the person's opening balance for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /**
     * Folds the due-total and carried figure fixed for one person, and their opening balance, into another's (a person with none fixed takes them as theirs): the
     * merged person leaves the period, their figure kept under the person they were merged into.
     */
    public void mergePersonTotal(Long from, Long into) {
        BigDecimal moved = closedPersonTotals.remove(from);
        if (moved != null) {
            closedPersonTotals.merge(into, moved, BigDecimal::add);
        }
        BigDecimal carried = closedPersonCarried.remove(from);
        if (carried != null) {
            closedPersonCarried.merge(into, carried, BigDecimal::add);
        }
        BigDecimal opening = personOpenings.remove(from);
        if (opening != null) {
            personOpenings.merge(into, opening, BigDecimal::add);
        }
    }

    /** Whether the date falls in the period. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Closes the period, fixing its due-total and each person's, and each person's carried figure. */
    public void close(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(total, personTotals, personCarried);
        this.closed = true;
        this.softClosed = false;
    }

    /**
     * Soft-closes the period, fixing its due-total and each person's, and each person's carried figure; claims due by a
     * date in it can still change.
     */
    public void softClose(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        fix(total, personTotals, personCarried);
        this.softClosed = true;
    }

    private void fix(BigDecimal total, Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        this.closedTotal = total;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedPersonCarried.clear();
        this.closedPersonCarried.putAll(personCarried);
    }

    /** Reopens the period, closed or soft-closed: its due-total and carried figures are live again. */
    public void reopen() {
        this.closed = false;
        this.softClosed = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
        this.closedPersonCarried.clear();
    }
}
