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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A rollover of a sheet's fiscal year: what it generated and the openings it set, so that it can be undone. */
@Entity
@Table(name = "rollover")
public class Rollover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The old fiscal year, by the calendar year it starts in. */
    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    /** The sheet's today when it was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    /** The period the openings were set in. */
    @Column(name = "opening_period_id")
    private Long openingPeriodId;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "rollover_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    /** The amount added to each person's opening balance, by person id. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "rollover_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, int fiscalYear, LocalDate madeOn, String madeBy, Long openingPeriodId,
            List<Long> periodIds, Map<Long, BigDecimal> openings) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
        this.madeOn = madeOn;
        this.madeBy = madeBy;
        this.openingPeriodId = openingPeriodId;
        this.periodIds.addAll(periodIds);
        this.openings.putAll(openings);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public int getFiscalYear() {
        return fiscalYear;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public String getMadeBy() {
        return madeBy;
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

    /** Mark the rollover undone. */
    public void undo() {
        this.undone = true;
    }
}
