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

/** A rate for a grade on a sheet: a percent with two decimals, applying from its from-date (from
 *  the beginning of time when it has none) until a later from-date of the grade takes over, or
 *  through its to-date when it has one. */
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

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    /** The day the rate applies from; null when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day the rate applies; null when it has no end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    /** Where a forward-only rate was saved in the order claims are added (see Claim#getAdded): it
     *  applies only to claims added after it; null when it applies to every claim. */
    @Column(name = "forward_after")
    private Long forwardAfter;

    /** How many versions the rate has had; this row is the newest (see {@link RateVersion}). */
    @Column(name = "version", nullable = false)
    private int version = 1;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, BigDecimal pct, LocalDate fromDate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.pct = pct;
        this.fromDate = fromDate;
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

    public Long getForwardAfter() {
        return forwardAfter;
    }

    public void setForwardAfter(Long forwardAfter) {
        this.forwardAfter = forwardAfter;
    }

    /** Whether the rate applies to a claim: it is not forward-only, or the claim was added after it
     *  was saved. */
    public boolean appliesTo(Claim claim) {
        return forwardAfter == null || claim.getAdded() > forwardAfter;
    }

    public int getVersion() {
        return version;
    }

    /** Make this row the next version of the rate; keep its current values first (see
     *  {@link RateVersion}). */
    public void nextVersion() {
        this.version++;
    }

    /** Make this row the older version again, taking back its values: the newest version is gone. */
    public void previousVersion(RateVersion older) {
        this.version = older.getVersion();
        this.pct = older.getPct();
        this.toDate = older.getToDate();
        this.forwardAfter = older.getForwardAfter();
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
