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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A sheet's last rollover, kept so it can be undone: who made it and on which of the sheet's todays, the fiscal
 * year rolled over, the periods it generated and, per person id, the opening balance it set in the new year's
 * first period.
 */
@Entity
@Table(name = "rollover_last")
public class LastRollover {

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "fy", nullable = false)
    private String fy;

    @Column(name = "first_period_id")
    private Long firstPeriodId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_last_period", joinColumns = @JoinColumn(name = "sheet_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_last_opening", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "opening", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected LastRollover() {
    }

    public LastRollover(String sheetId, String madeBy, LocalDate madeOn, String fy, Long firstPeriodId,
            Set<Long> periodIds, Map<Long, BigDecimal> openings) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fy = fy;
        this.firstPeriodId = firstPeriodId;
        this.periodIds = new HashSet<>(periodIds);
        this.openings = new HashMap<>(openings);
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

    public Long getFirstPeriodId() {
        return firstPeriodId;
    }

    public Set<Long> getPeriodIds() {
        return Set.copyOf(periodIds);
    }

    public Map<Long, BigDecimal> getOpenings() {
        return Map.copyOf(openings);
    }
}
