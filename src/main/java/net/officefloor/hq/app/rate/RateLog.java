package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One rate as it was set: the grade, its from-date and to-date, the percent, and the sheet's today
 * when it was set (none: from the beginning of time). Kept so the rates can be rebuilt as they stood
 * at the end of a day.
 */
@Entity
@Table(name = "rate_log")
public class RateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "forward_after")
    private Long forwardAfter;

    @Column(name = "made_on")
    private LocalDate madeOn;

    protected RateLog() {
    }

    public RateLog(Rate rate, LocalDate madeOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.fromDate = rate.getFromDate();
        this.toDate = rate.getToDate();
        this.pct = rate.getPct();
        this.forwardAfter = rate.getForwardAfter();
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    /** Whether it was set by the end of a day: the sheet's today then on or before it. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    /** The rate as it was set, not to be saved. */
    public Rate toRate() {
        Rate rate = new Rate(sheetId, grade, fromDate, pct);
        rate.setToDate(toDate);
        rate.setForwardAfter(forwardAfter);
        return rate;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }
}
