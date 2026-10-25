package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import net.officefloor.hq.app.person.Grade;

/** The sheet's rate for one grade: a percent with two decimals. */
@Entity
@Table(name = "rate")
@IdClass(Rate.Key.class)
public class Rate {

    /** A rate is kept once per grade on a sheet. */
    public record Key(String sheetId, Grade grade) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    protected Rate() {
    }

    public Rate(String sheetId, Grade grade, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        setPct(pct);
    }

    public String getSheetId() {
        return sheetId;
    }

    public Grade getGrade() {
        return grade;
    }

    public BigDecimal getPct() {
        return pct;
    }

    /** Change the percent; it is kept to two decimals. */
    public void setPct(BigDecimal pct) {
        this.pct = pct.setScale(2);
    }
}
