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
 * A sheet's rate for a grade (A, B or C): a percent with two decimals, in force from its from-date,
 * or from the beginning of time when it has none, through its to-date when it has one. It holds its
 * newest version, the one in force; the older ones are kept as {@link RateVersion}s.
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

    @Column(name = "pct", nullable = false, precision = 14, scale = 2)
    private BigDecimal pct;

    /** The first date the rate applies to; null when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last date the rate applies to; null when it has no end. */
    @Column(name = "to_date")
    private LocalDate toDate;

    /**
     * Set when the rate was saved with a from-date on or after the sheet's today: it is forward-only, applying
     * only to claims whose id is above this one (those added after it was saved). Null: it applies to every claim.
     */
    @Column(name = "forward_after")
    private Long forwardAfter;

    /** How many versions the rate has had, this one the newest; 1 when it was set once. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    public int getVersion() {
        return version;
    }

    /** Make this the next version: the caller keeps the current one as a {@link RateVersion} first. */
    public void nextVersion(BigDecimal pct, LocalDate toDate) {
        this.version++;
        this.pct = pct;
        this.toDate = toDate;
    }

    public Long getForwardAfter() {
        return forwardAfter;
    }

    /** Make the rate forward-only past the claim id given, or (null) apply it to every claim. */
    public Rate forwardAfter(Long claimId) {
        this.forwardAfter = claimId;
        return this;
    }

    /** Whether the rate applies to the claim: not forward-only, or the claim was added after it was saved (or is not stored yet). */
    public boolean appliesTo(Long claimId) {
        return forwardAfter == null || claimId == null || claimId > forwardAfter;
    }

    public BigDecimal getPct() {
        return pct;
    }
}
