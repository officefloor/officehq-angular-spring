package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A rate as it was set for a grade on a sheet, with the sheet's today then; kept in the order set. */
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
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    protected RateLog() {}

    public RateLog(Rate rate, LocalDate madeOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.from = rate.getFrom();
        this.to = rate.getTo();
        this.rate = rate.getRate();
        this.madeOn = madeOn;
    }

    public Long getId() {
        return id;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** The rate as it was set, never saved. */
    public Rate toRate() {
        return new Rate(sheetId, grade, from, to, rate);
    }
}
