package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.person.Grade;

/** One of the sheet's rates for a grade: a percent with two decimals, in force from its from-date
 *  (or from the beginning of time, when it has none) until the grade's next rate. */
@Entity
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    protected Rate() {
    }

    public Rate(String sheetId, Grade grade, LocalDate from, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        setPct(pct);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Grade getGrade() {
        return grade;
    }

    /** The date the rate is in force from, or null when it applies from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    public BigDecimal getPct() {
        return pct;
    }

    /** Change the percent; it is kept to two decimals. */
    public void setPct(BigDecimal pct) {
        this.pct = pct.setScale(2);
    }

    /** Whether the rate is in force on or before this date. */
    public boolean appliesBy(LocalDate date) {
        return from == null || !from.isAfter(date);
    }
}
