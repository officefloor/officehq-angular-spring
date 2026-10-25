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
import java.util.List;
import net.officefloor.hq.app.person.Grade;

/** One of the sheet's rates for a grade: a percent with two decimals, in force from its from-date
 *  (or from the beginning of time, when it has none) until the grade's next rate, and no later than
 *  its to-date when it has one. */
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

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    protected Rate() {
    }

    public Rate(String sheetId, Grade grade, LocalDate from, LocalDate to, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
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

    /** The last date the rate is in force, or null when it runs until the grade's next rate. */
    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
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

    /** Whether the rate's to-date has passed by this date. */
    public boolean endedBy(LocalDate date) {
        return to != null && to.isBefore(date);
    }

    /** Of one grade's rates (from the start first, then by from-date), the one in force on the date:
     *  the one with the latest from-date on or before it, unless its to-date has passed; null when none is. */
    public static Rate inForce(Iterable<Rate> gradeRates, LocalDate date) {
        Rate inForce = null;
        for (Rate rate : gradeRates) {
            if (rate.appliesBy(date)) inForce = rate;
        }
        return inForce == null || inForce.endedBy(date) ? null : inForce;
    }

    /** Of one grade's rates on the sheet and on each of its parents in turn (nearest first), the one in force on
     *  the date: the sheet's own, else the nearest parent's that has one in force then; null when none has. */
    public static Rate inForceInherited(List<? extends Iterable<Rate>> levels, LocalDate date) {
        for (Iterable<Rate> gradeRates : levels) {
            Rate rate = inForce(gradeRates, date);
            if (rate != null) return rate;
        }
        return null;
    }
}
