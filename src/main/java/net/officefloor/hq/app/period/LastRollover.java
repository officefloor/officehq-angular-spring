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
 * The last rollover on a sheet, kept so the steward who made it can undo it on the same
 * sheet-today: the periods it generated, and each person's opening balance it added to the
 * opening period.
 */
@Entity
@Table(name = "rollover_last")
public class LastRollover {

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    /** The sheet's today when it was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    /** The fiscal year rolled over, FY&lt;year&gt;. */
    @Column(name = "fy", nullable = false)
    private String fy;

    /** The period the opening balances went into; null when none was set. */
    @Column(name = "opening_period_id")
    private Long openingPeriodId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_last_period", joinColumns = @JoinColumn(name = "sheet_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_last_opening", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected LastRollover() {
    }

    public LastRollover(String sheetId, String madeBy, LocalDate madeOn, String fy) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fy = fy;
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

    public void setOpeningPeriodId(Long openingPeriodId) {
        this.openingPeriodId = openingPeriodId;
    }

    public Set<Long> getPeriodIds() {
        return periodIds;
    }

    public Map<Long, BigDecimal> getOpenings() {
        return openings;
    }
}
