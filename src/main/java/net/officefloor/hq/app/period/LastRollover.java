package net.officefloor.hq.app.period;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The sheet's last rollover, kept so the steward who made it can undo it on the sheet-today it
 *  was made: the periods it generated, and the amounts it added to each person's opening balance
 *  for the period it carried openings into. */
@Entity
@Table(name = "rollover")
public class LastRollover {

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Column(name = "fy", nullable = false)
    private int fy;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @Column(name = "opened_period_id")
    private Long openedPeriodId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "sheet_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new LinkedHashMap<>();

    protected LastRollover() {
    }

    public LastRollover(String sheetId) {
        this.sheetId = sheetId;
    }

    /** Make this the record of a new rollover, forgetting the one before. */
    public void remember(int fy, String madeBy, LocalDate madeOn, Set<Long> periodIds, Long openedPeriodId,
            Map<Long, BigDecimal> openings) {
        this.fy = fy;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.undone = false;
        this.openedPeriodId = openedPeriodId;
        this.periodIds.clear();
        this.periodIds.addAll(periodIds);
        this.openings.clear();
        this.openings.putAll(openings);
    }

    public String getSheetId() {
        return sheetId;
    }

    /** The fiscal year rolled over. */
    public int getFy() {
        return fy;
    }

    public String getMadeBy() {
        return madeBy;
    }

    /** The sheet-today the rollover was made on. */
    public LocalDate getMadeOn() {
        return madeOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public void markUndone() {
        this.undone = true;
    }

    /** The period the rollover carried openings into; null when it carried none. */
    public Long getOpenedPeriodId() {
        return openedPeriodId;
    }

    /** The ids of the periods the rollover generated. */
    public Set<Long> getPeriodIds() {
        return periodIds;
    }

    /** The amount the rollover added to each person's opening balance, by person id. */
    public Map<Long, BigDecimal> getOpenings() {
        return openings;
    }
}
