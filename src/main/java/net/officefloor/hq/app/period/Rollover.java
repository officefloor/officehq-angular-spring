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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A rollover of a fiscal year on a sheet: the old fiscal year, who made it and the sheet's today then, the periods it
 * generated and the opening balances it added, and whether it has been undone.
 */
@Entity
@Table(name = "rollover")
public class Rollover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "fy", nullable = false)
    private String fy;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_period", joinColumns = @JoinColumn(name = "rollover_id"))
    @Column(name = "period_id")
    private Set<Long> periodIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rollover_opening", joinColumns = @JoinColumn(name = "rollover_id"))
    private List<RolloverOpening> openings = new ArrayList<>();

    protected Rollover() {
    }

    public Rollover(String sheetId, String fy, String madeBy, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.fy = fy;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getFy() {
        return fy;
    }

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public void markUndone() {
        this.undone = true;
    }

    public Set<Long> getPeriodIds() {
        return Set.copyOf(periodIds);
    }

    public void addPeriod(Long periodId) {
        periodIds.add(periodId);
    }

    public List<RolloverOpening> getOpenings() {
        return List.copyOf(openings);
    }

    public void addOpening(Long periodId, Long personId, BigDecimal amount) {
        openings.add(new RolloverOpening(periodId, personId, amount));
    }
}
