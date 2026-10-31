package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A sheet's rate for one grade: a percent with two decimals, in force from its from-date (or, with
 *  none, from the beginning of time) until the grade's next rate takes over, and never after its
 *  to-date (if it has one). */
@Entity
@Table(name = "rate")
public class Rate {

    /** Rates in the order they take effect: the one with no from-date first, then by from-date. */
    public static final Comparator<Rate> BY_FROM =
            Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate fromDate, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.rate = rate;
    }

    /** The rate among a grade's rates in force on a date: the one with the latest from-date on or
     *  before it, unless the date is after its to-date; none when the date is before every one of
     *  them, or that rate has ended by then (an earlier rate does not take over again). */
    public static Optional<Rate> inForceOn(List<Rate> gradeRates, LocalDate date) {
        return gradeRates.stream()
                .filter(r -> r.fromDate == null || !r.fromDate.isAfter(date))
                .max(BY_FROM)
                .filter(r -> r.toDate == null || !date.isAfter(r.toDate));
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

    /** The day the rate takes effect; null when it applies from the beginning of time. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** The last day the rate applies; null when it does not end. */
    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
