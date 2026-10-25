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
import java.util.Locale;
import java.util.Map;

/** A sheet's period: a name, unique on the sheet without regard to case, and the dates it runs from
 * and to, both inclusive. A claim belongs to the period whose dates contain its due-by. Once closed, its
 * due-total, and each person's due-total for it and carried figure into it, are the ones fixed when it was closed, until it is
 * reopened. A soft-closed period is closed and its figures fixed the same way, but claims due by a date in it can
 * still change. Each pool member's cut as it stood when the period was closed or soft-closed is fixed with it, until
 * it is reopened. */
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
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** Whether the period, while closed, is only soft-closed. */
    @Column(name = "soft", nullable = false)
    private boolean soft;

    /** Whether a steward has locked the period: a locked period cannot be reopened. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    /** The most OPEN or PENDING claims each person may have in the period; null for no cap. */
    @Column(name = "claim_cap")
    private Integer cap;

    /** The due-total fixed when the period was closed; null while it is open. */
    @Column(name = "closed_total", precision = 12, scale = 2)
    private BigDecimal closedTotal;

    /** Each person's due-total for the period fixed when it was closed, by person id; empty while it
     * is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_total", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "total", precision = 12, scale = 2, nullable = false)
    private Map<Long, BigDecimal> closedPersonTotals = new HashMap<>();

    /** Each person's carried figure into the period fixed when it was closed, by person id; empty while
     * it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_carried", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "carried", precision = 12, scale = 2, nullable = false)
    private Map<Long, BigDecimal> closedPersonCarried = new HashMap<>();

    /** Each pool member's cut fixed when the period was closed, by the pool's and the member's keys joined by
     * '/' (see {@code PoolService}); a member with none then is left out. Empty while it is open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_pool_cut", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "cut_key")
    @Column(name = "cut", precision = 12, scale = 2, nullable = false)
    private Map<String, BigDecimal> closedPoolCuts = new HashMap<>();

    /** Each person's opening balance for the period, pasted in, by person id: added to their carried
     * figure into it. Kept whether it is open or closed. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_opening", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", precision = 12, scale = 2, nullable = false)
    private Map<Long, BigDecimal> personOpenings = new HashMap<>();

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

    /** The name as compared: without the spaces around it, in lower case. */
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

    public boolean isSoft() {
        return soft;
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

    public Map<Long, BigDecimal> getClosedPersonTotals() {
        return Map.copyOf(closedPersonTotals);
    }

    public Map<Long, BigDecimal> getClosedPersonCarried() {
        return Map.copyOf(closedPersonCarried);
    }

    public Map<String, BigDecimal> getClosedPoolCuts() {
        return Map.copyOf(closedPoolCuts);
    }

    public Map<Long, BigDecimal> getPersonOpenings() {
        return Map.copyOf(personOpenings);
    }

    /** Add {@code amount} to the opening balance of the person {@code personId} for the period. */
    public void addOpening(Long personId, BigDecimal amount) {
        personOpenings.merge(personId, amount, BigDecimal::add);
    }

    /** Take {@code amount} back off the opening balance of the person {@code personId} for the period;
     * a balance that comes to zero is no opening balance. */
    public void takeOpening(Long personId, BigDecimal amount) {
        BigDecimal left = personOpenings.getOrDefault(personId, BigDecimal.ZERO).subtract(amount);
        if (left.signum() == 0) personOpenings.remove(personId);
        else personOpenings.put(personId, left);
    }

    /** Close the period, fixing each person's due-total for it at {@code personTotals} and their carried
     * figure into it at {@code personCarried}, by person id, and its own due-total at the totals' sum. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried) {
        close(personTotals, personCarried, false);
    }

    /** Close the period as {@link #close(Map, Map)} does, soft-closed when {@code soft}. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried, boolean soft) {
        close(personTotals, personCarried, Map.of(), soft);
    }

    /** Close the period as {@link #close(Map, Map, boolean)} does, fixing the pool members' cuts at
     * {@code poolCuts}. */
    public void close(Map<Long, BigDecimal> personTotals, Map<Long, BigDecimal> personCarried,
            Map<String, BigDecimal> poolCuts, boolean soft) {
        this.closedPoolCuts.clear();
        this.closedPoolCuts.putAll(poolCuts);
        this.closed = true;
        this.soft = soft;
        this.closedPersonTotals.clear();
        this.closedPersonTotals.putAll(personTotals);
        this.closedPersonCarried.clear();
        this.closedPersonCarried.putAll(personCarried);
        this.closedTotal = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Forget the due-total and carried figure fixed, and the opening balance, for the person {@code personId}, who leaves the
     * sheet. The period's own due-total is unchanged. */
    public void dropPersonTotal(Long personId) {
        closedPersonTotals.remove(personId);
        closedPersonCarried.remove(personId);
        personOpenings.remove(personId);
    }

    /** Fold the due-total and carried figure fixed, and the opening balance, for the person {@code from} into the ones for
     * {@code into}: the person {@code from} is merged into {@code into}. The period's own due-total is
     * unchanged. */
    public void mergePersonTotal(Long from, Long into) {
        BigDecimal moved = closedPersonTotals.remove(from);
        if (moved != null) closedPersonTotals.merge(into, moved, BigDecimal::add);
        BigDecimal carried = closedPersonCarried.remove(from);
        if (carried != null) closedPersonCarried.merge(into, carried, BigDecimal::add);
        BigDecimal opening = personOpenings.remove(from);
        if (opening != null) personOpenings.merge(into, opening, BigDecimal::add);
    }

    /** Reopen the period: its due-total and its pool members' cuts are live again. */
    public void reopen() {
        this.closed = false;
        this.soft = false;
        this.closedTotal = null;
        this.closedPersonTotals.clear();
        this.closedPersonCarried.clear();
        this.closedPoolCuts.clear();
    }

    /** Whether this period shares a day with the dates {@code from} to {@code to}, both inclusive. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !this.from.isAfter(to) && !from.isAfter(this.to);
    }
}
