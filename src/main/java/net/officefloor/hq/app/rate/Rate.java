package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A rate for a grade (A, B or C) on a sheet: a percent with two decimals, applying from its
 * from-date, or from the beginning of time when it has none, through its to-date, or on with no end
 * when it has none. A rate saved with a from-date on or after the sheet's today is forward-only: it
 * applies only to claims added after it was saved (see {@link #seenBy}).
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

    @Column(name = "pct", nullable = false)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * Where in the order claims are added the newest version was saved when it is forward-only: it
     * applies only to claims added after; null when it applies to every claim.
     */
    @Column(name = "forward_after")
    private Long forwardAfter;

    /** The rate's older versions, newest first. */
    @OneToMany(fetch = FetchType.EAGER)
    @JoinColumn(name = "rate_id", insertable = false, updatable = false)
    @OrderBy("version DESC")
    private List<RateVersion> older = new ArrayList<>();

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

    /** The date the rate applies from; null when it applies from the beginning of time. */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /** Whether the rate applies to a claim on a date: it has no from-date, or one on or before the date. */
    public boolean appliesOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }

    /** The last date the rate applies on; null when it has no end. */
    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    /** Whether the rate has ended by a date: it has a to-date before the date. */
    public boolean endedBy(LocalDate date) {
        return toDate != null && toDate.isBefore(date);
    }

    /** The number of the version the rate holds, its newest: 1 for a rate never replaced. */
    public int getVersion() {
        return version;
    }

    /**
     * Replace the rate's value with a new version: the value it held is returned as an older version
     * to keep, and the rate holds the new value as the next version.
     */
    public RateVersion replace(BigDecimal pct, LocalDate toDate, Long forwardAfter) {
        RateVersion replaced = new RateVersion(id, version, this.pct, this.toDate, this.forwardAfter);
        older.add(0, replaced);
        this.pct = pct;
        this.toDate = toDate;
        this.forwardAfter = forwardAfter;
        this.version++;
        return replaced;
    }

    /**
     * Withdraw the newest version: the newest older version, when there is one, is held again as the
     * newest and returned (to be dropped from the older versions kept); empty when there is none.
     */
    public Optional<RateVersion> withdraw() {
        if (older.isEmpty()) {
            return Optional.empty();
        }
        RateVersion restored = older.remove(0);
        this.pct = restored.getPct();
        this.toDate = restored.getToDate();
        this.forwardAfter = restored.getForwardAfter();
        this.version = restored.getVersion();
        return Optional.of(restored);
    }

    /** Where the newest version was saved in the order claims are added when forward-only; else null. */
    public Long getForwardAfter() {
        return forwardAfter;
    }

    public void setForwardAfter(Long forwardAfter) {
        this.forwardAfter = forwardAfter;
    }

    /** The rate's older versions, newest first. */
    public List<RateVersion> getOlder() {
        return List.copyOf(older);
    }

    /** Hold these older versions (newest first), for a rate held in no store. */
    public void keepOlder(List<RateVersion> versions) {
        older = new ArrayList<>(versions);
    }

    /**
     * The rate as a claim added at a place in the order claims are added sees it (null: a claim not yet
     * stored, added after every rate): its newest version unless that is forward-only and saved after the
     * claim was added, else its newest older version that applies to the claim; empty when none does.
     */
    public Optional<Rate> seenBy(Long added) {
        if (appliesTo(forwardAfter, added)) {
            return Optional.of(this);
        }
        return older.stream().filter(v -> appliesTo(v.getForwardAfter(), added)).findFirst().map(v -> {
            Rate seen = new Rate(sheetId, grade, fromDate, v.getPct());
            seen.setToDate(v.getToDate());
            return seen;
        });
    }

    private static boolean appliesTo(Long forwardAfter, Long added) {
        return forwardAfter == null || added == null || added > forwardAfter;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
