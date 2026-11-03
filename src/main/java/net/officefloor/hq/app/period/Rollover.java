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

/** A rollover made on a sheet: what it generated and the opening balances it set, kept so the last
 *  one can be undone by the user who made it on the sheet-today it was made. */
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

    /** The sheet's today when it was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    /** The fiscal year it rolled over (FYyyyy). */
    @Column(name = "fy", nullable = false)
    private String fy;

    /** The new year's first period, whose opening balances it added to. */
    @Column(name = "opening_period_id", nullable = false)
    private Long openingPeriodId;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    /** The ids of the periods it generated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "rollover_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    /** The opening balance it added for each person, by the person's id. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "rollover_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, String madeBy, LocalDate madeOn, String fy, Long openingPeriodId,
            Set<Long> periodIds, Map<Long, BigDecimal> openings) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fy = fy;
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

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public String getFy() {
        return fy;
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

    /** Mark it undone: it cannot be undone again. */
    public void undo() {
        this.undone = true;
    }
}
