package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/** A sheet's rate for one grade: a percent with two decimals. */
@Entity
@Table(name = "rate")
@IdClass(Rate.Key.class)
public class Rate {

    /** The key of a rate: its sheet and grade. */
    public static class Key implements Serializable {

        private String sheetId;
        private String grade;

        public Key() {
        }

        public Key(String sheetId, String grade) {
            this.sheetId = sheetId;
            this.grade = grade;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key k && Objects.equals(sheetId, k.sheetId) && Objects.equals(grade, k.grade);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, grade);
        }
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.rate = rate;
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
