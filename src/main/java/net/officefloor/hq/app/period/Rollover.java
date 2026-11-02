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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The sheet's last rollover: the fiscal year rolled over, who made it and on what sheet-today, the periods it
 * generated and the opening balances it added, by person, to the new year's first period.
 */
@Entity
@Table(name = "sheet_rollover")
public class Rollover {

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    /** The period the opening balances were added to; null when there was none. */
    @Column(name = "opening_period_id")
    private Long openingPeriodId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_rollover_period", joinColumns = @JoinColumn(name = "sheet_id"))
    @Column(name = "period_id", nullable = false)
    private List<Long> periodIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_rollover_opening", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "person_id")
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private Map<Long, BigDecimal> openings = new TreeMap<>();

    protected Rollover() {}

    public Rollover(String sheetId, int fiscalYear, String madeBy, LocalDate madeOn, Long openingPeriodId,
            List<Long> periodIds, Map<Long, BigDecimal> openings) {
        this.sheetId = sheetId;
        this.fiscalYear = fiscalYear;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.openingPeriodId = openingPeriodId;
        this.periodIds = new ArrayList<>(periodIds);
        this.openings = new TreeMap<>(openings);
    }

    public String getSheetId() { return sheetId; }

    public int getFiscalYear() { return fiscalYear; }

    public String getMadeBy() { return madeBy; }

    public LocalDate getMadeOn() { return madeOn; }

    public Long getOpeningPeriodId() { return openingPeriodId; }

    public List<Long> getPeriodIds() { return periodIds; }

    public Map<Long, BigDecimal> getOpenings() { return openings; }
}
