package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One of the sheet's rates for a grade: a percent with two decimals, in force from its from-date
 *  (from the beginning of time when it has none) through its to-date (for good when it has none). */
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

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
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

    /** The first date the rate is in force; null when it applies from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    /** Whether the rate is in force on a date (it may be overtaken by a later one). */
    public boolean appliesOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }

    /** The last date the rate applies; null when it applies for good. */
    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    /** Whether the rate still applies on a date it is in force (not past its to-date). */
    public boolean runsThrough(LocalDate date) {
        return to == null || !to.isBefore(date);
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
