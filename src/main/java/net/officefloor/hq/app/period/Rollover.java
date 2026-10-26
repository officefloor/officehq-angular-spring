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

/** One rollover of a fiscal year: who made it on which sheet's today, the periods it generated and the
 *  opening balances it added to the new year's first period, so it can be undone. */
@Entity
@Table(name = "rollover")
public class Rollover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The calendar year the rolled-over fiscal year starts in. */
    @Column(name = "fy_year", nullable = false)
    private int year;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    /** The sheet's today when the rollover was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    /** The period the opening balances were added to; null when there was none. */
    @Column(name = "opening_period")
    private Long openingPeriodId;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    /** The ids of the periods the rollover generated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "rollover_id"))
    @Column(name = "period_id", nullable = false)
    private Set<Long> periodIds = new HashSet<>();

    /** The opening balance the rollover added for each person, by person id. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "rollover_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, int year, String madeBy, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.year = year;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public int getYear() {
        return year;
    }

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public Long getOpeningPeriodId() {
        return openingPeriodId;
    }

    public void setOpeningPeriodId(Long openingPeriodId) {
        this.openingPeriodId = openingPeriodId;
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }

    public Set<Long> getPeriodIds() {
        return periodIds;
    }

    public Map<Long, BigDecimal> getOpenings() {
        return openings;
    }
}
