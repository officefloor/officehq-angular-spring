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
 * A line of a sheet's rate log: a rate set for a grade and from-date, as it was set, on the sheet's
 * today then (null: before this was kept). A later line for the same grade and from-date replaced it.
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

    @Column(name = "set_on")
    private LocalDate setOn;

    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateLog() {}

    public RateLog(Rate rate, LocalDate setOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.fromDate = rate.getFromDate();
        this.toDate = rate.getToDate();
        this.pct = rate.getPct();
        this.setOn = setOn;
        this.forwardAfter = rate.getForwardAfter();
    }

    public String getGrade() { return grade; }

    public LocalDate getFromDate() { return fromDate; }

    /** Whether it was set on or before the date. */
    public boolean setBy(LocalDate date) { return setOn == null || !setOn.isAfter(date); }

    /** The rate as this line set it, as the newest of the given number of versions. */
    public Rate toRate(int versions) { return new Rate(sheetId, grade, fromDate, toDate, pct, versions, forwardAfter); }
}
