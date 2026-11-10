package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.claim.Claim;

/**
 * A sheet's rate for a grade: a percent with two decimals, in force from its from-date (none: from
 * the beginning of time) until the grade's next rate, or through its to-date when it has one. Its
 * version counts how many times it has been set: the older ones are kept as {@link RateVersion}s.
 * Saved with a from-date on or after the sheet's today it is forward-only: it applies only to the
 * claims added after it was saved.
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

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "version", nullable = false)
    private int version = 1;

    /** For a forward-only rate, the point it was saved at: it applies to claims added after it; none applies to every claim. */
    @Column(name = "forward_after")
    private Long forwardAfter;

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

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    /** Whether the rate is still in force on a date: it has no to-date, or the date is on or before it. */
    public boolean runsThrough(LocalDate date) {
        return toDate == null || !date.isAfter(toDate);
    }

    /** How many versions the rate has had: 1 when first set, one more each time it is set again. */
    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Long getForwardAfter() {
        return forwardAfter;
    }

    public void setForwardAfter(Long forwardAfter) {
        this.forwardAfter = forwardAfter;
    }

    /** Whether the rate applies to a claim: it is not forward-only, or the claim was added after it was saved. */
    public boolean appliesTo(Claim claim) {
        return forwardAfter == null || claim.getAddedSeq() != null && claim.getAddedSeq() > forwardAfter;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
