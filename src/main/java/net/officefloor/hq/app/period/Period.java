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
 * due-total it had when it was closed, and each person's due-total for it at that moment.
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

    @Column(name = "closed_due")
    private BigDecimal closedDue;

    /** Each person's due-total fixed when the period was closed, by person id; empty while open. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "period_person_due", joinColumns = @JoinColumn(name = "period_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "due", nullable = false)
    private Map<Long, BigDecimal> closedDues = new HashMap<>();

    /** The last number given to a claim opened in this period under numbering per period. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    protected Period() {
    }

    public Period(String sheetId, String name, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.name = name;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    public boolean isClosed() {
        return closed;
    }

    public BigDecimal getClosedDue() {
        return closedDue;
    }

    public Map<Long, BigDecimal> getClosedDues() {
        return closedDues;
    }

    /** Give out the next claim number within this period. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }

    /**
     * Close the period, fixing each person's due-total for it at this moment; its own due-total is
     * their sum.
     */
    public void close(Map<Long, BigDecimal> dues) {
        this.closed = true;
        this.closedDues.clear();
        this.closedDues.putAll(dues);
        this.closedDue = dues.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Reopen the period: its due-total is live again. */
    public void reopen() {
        this.closed = false;
        this.closedDue = null;
        this.closedDues.clear();
    }
}
