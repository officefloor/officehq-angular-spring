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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A rollover of a sheet's fiscal year, kept so the last one can be undone: who made it and on which
 * sheet-today, the periods it generated, and the opening balance it added for each person, by person id,
 * in the new year's first period. */
@Entity
@Table(name = "rollover")
public class Rollover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    /** The period the opening balances were added in; null when none was. */
    @Column(name = "opening_period_id")
    private Long openingPeriodId;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "rollover_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "rollover_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, String madeBy, LocalDate madeOn, int fiscalYear) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fiscalYear = fiscalYear;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public int getFiscalYear() {
        return fiscalYear;
    }

    public Long getOpeningPeriodId() {
        return openingPeriodId;
    }

    public boolean isUndone() {
        return undone;
    }

    public Set<Long> getPeriodIds() {
        return Set.copyOf(periodIds);
    }

    public Map<Long, BigDecimal> getOpenings() {
        return Map.copyOf(openings);
    }

    public void addPeriod(Long periodId) {
        periodIds.add(periodId);
    }

    /** Record that {@code amount} was added to the opening balance of the person {@code personId} in the
     * period {@code periodId}. */
    public void addOpening(Long periodId, Long personId, BigDecimal amount) {
        openingPeriodId = periodId;
        openings.merge(personId, amount, BigDecimal::add);
    }

    public void undo() {
        undone = true;
    }
}
