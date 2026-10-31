package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A sheet's rate for one grade: a percent with two decimals, in force from its from-date (or, with
 *  none, from the beginning of time) until the grade's next rate takes over, and never after its
 *  to-date (if it has one). A forward-only rate (one saved with a from-date on or after the sheet's today) applies
 *  only to claims added after it was saved; a claim already there keeps the version that applied to it before. */
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

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "claims_after")
    private Long claimsAfter;

    /** The older versions a claim the newest does not apply to may take, newest first, down to the first that
     *  applies to every claim: as {@link RateChains} reads them; none until it does. */
    @Transient
    private List<RateVersion> earlier = List.of();

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
        return inForceOn(gradeRates, date, null);
    }

    /** The rate among a grade's rates in force on a date for a claim (null: one not saved yet, which every rate
     *  applies to), as {@link #inForceOn(List, LocalDate)}, each rate as the claim takes it (see {@link #forClaim}). */
    public static Optional<Rate> inForceOn(List<Rate> gradeRates, LocalDate date, Long claimId) {
        return gradeRates.stream()
                .flatMap(r -> r.forClaim(claimId).stream())
                .filter(r -> r.fromDate == null || !r.fromDate.isAfter(date))
                .max(BY_FROM)
                .filter(r -> r.toDate == null || !date.isAfter(r.toDate));
    }

    /** The rate as a claim (null: one not saved yet) takes it: this version when it applies to the claim (it is not
     *  forward-only, or the claim was added after it was saved), else the newest older version that does, as an
     *  unsaved stand-in with that version's value and to-date; none when no version applies to the claim. */
    public Optional<Rate> forClaim(Long claimId) {
        if (claimId == null || claimsAfter == null || claimId > claimsAfter) {
            return Optional.of(this);
        }
        for (RateVersion older : earlier) {
            if (older.getClaimsAfter() == null || claimId > older.getClaimsAfter()) {
                Rate standIn = new Rate(sheetId, grade, fromDate, older.getRate());
                standIn.toDate = older.getToDate();
                standIn.version = older.getVersion();
                return Optional.of(standIn);
            }
        }
        return Optional.empty();
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

    /** The number of the version in force: how many versions the rate has had, the newest being this one. */
    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /** For a forward-only rate, the highest claim id there was when it was saved: it applies only to claims after
     *  it. Null when it applies to every claim. */
    public Long getClaimsAfter() {
        return claimsAfter;
    }

    public void setClaimsAfter(Long claimsAfter) {
        this.claimsAfter = claimsAfter;
    }

    /** The older versions a claim the newest does not apply to may take, newest first (see {@link #forClaim}). */
    public List<RateVersion> getEarlier() {
        return earlier;
    }

    public void setEarlier(List<RateVersion> earlier) {
        this.earlier = List.copyOf(earlier);
    }
}
