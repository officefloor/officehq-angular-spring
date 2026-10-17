package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** The rate for a grade on a sheet: a percent (0.00 or more, two decimals). */
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

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.rate = rate;
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

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
