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
 * A rate as it was set for a grade on a sheet: its from- and to-date, its percent, and the sheet's today when it was
 * set (null: before that was kept). Kept in the order set, so the rates as of a day can be read back.
 */
@Entity
@Table(name = "rate_change")
public class RateChange {

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

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "made_on")
    private LocalDate madeOn;

    /** The claim mark a forward-only rate was set at; null for one applying to every claim. */
    @Column(name = "after_mark")
    private Long afterMark;

    protected RateChange() {
    }

    public RateChange(Rate rate, LocalDate madeOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.from = rate.getFrom();
        this.to = rate.getTo();
        this.pct = rate.getPct();
        this.madeOn = madeOn;
        this.afterMark = rate.getAfterMark();
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    /** Whether the rate was set while the sheet's today was on or before the day. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    /** The rate as this change set it. Not to be saved. */
    public Rate rate() {
        Rate rate = new Rate(sheetId, grade, from, pct);
        rate.setTo(to);
        rate.setAfterMark(afterMark);
        return rate;
    }
}
