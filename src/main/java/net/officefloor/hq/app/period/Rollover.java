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

/**
 * A rollover of a fiscal year on a sheet, kept so the last one can be undone: who made it, on which
 * of the sheet's days, the periods it generated and the opening balances it added to its opening
 * period.
 */
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

    /** The sheet's today when the rollover was made. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    /** The fiscal year rolled over (the calendar year it starts in). */
    @Column(name = "fy", nullable = false)
    private int fy;

    /** The period the opening balances were added to; null when none were. */
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
    @Column(name = "amount", nullable = false)
    private Map<Long, BigDecimal> openings = new HashMap<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, String madeBy, LocalDate madeOn, int fy) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fy = fy;
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

    public int getFy() {
        return fy;
    }

    public Long getOpeningPeriodId() {
        return openingPeriodId;
    }

    public boolean isUndone() {
        return undone;
    }

    /** Mark the rollover undone. */
    public void undo() {
        this.undone = true;
    }

    /** The ids of the periods the rollover generated. */
    public Set<Long> getPeriodIds() {
        return Set.copyOf(periodIds);
    }

    /** Record a period the rollover generated. */
    public void addPeriod(long periodId) {
        periodIds.add(periodId);
    }

    /** The opening balances the rollover added to its opening period, by person id. */
    public Map<Long, BigDecimal> getOpenings() {
        return Map.copyOf(openings);
    }

    /** Record an opening balance the rollover added for a person to the given period. */
    public void addOpening(long periodId, long personId, BigDecimal amount) {
        this.openingPeriodId = periodId;
        openings.merge(personId, amount, BigDecimal::add);
    }
}
