package net.officefloor.hq.app.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The last rollover made on a sheet: who made it, the sheet's today then, the fiscal year rolled over, the periods it
 * generated and the opening balances it set, so it can be undone.
 */
@Entity
@Table(name = "period_rollover")
public class PeriodRollover {

    /** An opening balance the rollover set: the amount added to the person's opening for the period. */
    public record Opening(Long periodId, Long personId, BigDecimal amount) {
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "period_ids", nullable = false)
    private String periodIds;

    @Column(name = "openings", nullable = false)
    private String openings;

    protected PeriodRollover() {
    }

    public PeriodRollover(String sheetId, String madeBy, LocalDate madeOn, int fiscalYear, List<Long> periodIds,
            List<Opening> openings) {
        this.sheetId = sheetId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.fiscalYear = fiscalYear;
        this.periodIds = periodIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        this.openings = openings.stream()
                .map(o -> o.periodId() + ":" + o.personId() + ":" + o.amount().toPlainString())
                .collect(Collectors.joining(","));
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

    /** The ids of the periods the rollover generated. */
    public List<Long> getPeriodIds() {
        return Arrays.stream(periodIds.split(",")).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
    }

    /** The opening balances the rollover set. */
    public List<Opening> getOpenings() {
        return Arrays.stream(openings.split(",")).filter(s -> !s.isEmpty()).map(s -> s.split(":"))
                .map(p -> new Opening(Long.valueOf(p[0]), Long.valueOf(p[1]), new BigDecimal(p[2]))).toList();
    }
}
