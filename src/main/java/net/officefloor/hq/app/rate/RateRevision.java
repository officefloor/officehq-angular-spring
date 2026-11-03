package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A revision of a rate in its sheet's history: a grade's percent from a from-date (null: the
 *  beginning of time) through its to-date after a change, recorded on the sheet's today then. */
@Entity
@Table(name = "rate_revision")
public class RateRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateRevision() {
    }

    /** A revision of the rate as it is now, recorded on the day. */
    public RateRevision(Rate rate, LocalDate recordedOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.fromDate = rate.getFromDate();
        this.recordedOn = recordedOn;
        this.pct = rate.getPct();
        this.toDate = rate.getToDate();
        this.forwardAfter = rate.getForwardAfter();
    }

    /** Which rate the revision is of: its grade and from-date ("" for none). */
    public String key() {
        return key(grade, fromDate);
    }

    /** Which rate a rate is (see {@link #key()}). */
    public static String key(Rate rate) {
        return key(rate.getGrade(), rate.getFromDate());
    }

    private static String key(String grade, LocalDate fromDate) {
        return grade + ":" + (fromDate == null ? "" : fromDate);
    }

    /** Whether the rate is now as this revision has it. */
    public boolean matches(Rate rate) {
        return pct.compareTo(rate.getPct()) == 0 && Objects.equals(toDate, rate.getToDate())
                && Objects.equals(forwardAfter, rate.getForwardAfter());
    }

    /** The rate as this revision has it, detached: for working out figures only, never saved. */
    public Rate toRate() {
        Rate rate = new Rate(sheetId, grade, pct, fromDate);
        rate.setToDate(toDate);
        rate.setForwardAfter(forwardAfter);
        return rate;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }
}
