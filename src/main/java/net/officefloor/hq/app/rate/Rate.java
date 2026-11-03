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
 * A sheet's rate for a grade: a percent with two decimals, in force from its from-date (none: from
 * the beginning of time) until the grade's next rate.
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

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
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

    public LocalDate getFromDate() {
        return fromDate;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
