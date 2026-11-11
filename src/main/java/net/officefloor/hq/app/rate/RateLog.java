package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One setting of a grade's rate: its from- and to-dates and percent as set, and the sheet's today when it was set. */
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

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    @Column(name = "set_on", nullable = false)
    private LocalDate setOn;

    protected RateLog() {
    }

    RateLog(Rate rate, LocalDate setOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.fromDate = rate.getFromDate();
        this.toDate = rate.getToDate();
        this.pct = rate.getPct();
        this.setOn = setOn;
    }

    public Long getId() {
        return id;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public LocalDate getSetOn() {
        return setOn;
    }
}
