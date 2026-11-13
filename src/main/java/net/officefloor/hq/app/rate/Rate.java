package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.history.History;

/** The sheet's rate for a grade from a date (or from the beginning of time when it has none),
 *  perhaps through a to-date: a percent (0.00 or more, two decimals). */
@Entity
@EntityListeners(History.class)
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The first day the rate applies; none when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day the rate applies; none when it applies on for good. */
    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    /** How many versions the rate has had: this, the newest, and the older ones it replaced. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    /** Whether the rate still applies on a date on or after its from-date: not past its to-date. */
    public boolean coversThrough(LocalDate date) {
        return toDate == null || !date.isAfter(toDate);
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public int getVersion() {
        return version;
    }

    /** Replace the rate with a newer version, through a to-date; the caller keeps the one it replaces. */
    public void newVersion(BigDecimal rate, LocalDate toDate) {
        this.rate = rate;
        this.toDate = toDate;
        this.version++;
    }
}
