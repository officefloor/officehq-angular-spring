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
 * A rate for a grade (A, B or C) on a sheet: a percent with two decimals, applying from its
 * from-date, or from the beginning of time when it has none.
 */
@Entity
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "pct", nullable = false)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate fromDate;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate fromDate, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.pct = pct;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getGrade() {
        return grade;
    }

    /** The date the rate applies from; null when it applies from the beginning of time. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** Whether the rate applies to a claim on a date: it has no from-date, or one on or before the date. */
    public boolean appliesOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
