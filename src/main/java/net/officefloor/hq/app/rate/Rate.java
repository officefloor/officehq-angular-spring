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

/**
 * A rate for a grade on a sheet: a percent with two decimals, in force from its from-date (or from the
 * beginning of time when it has none) until the grade's next later-dated rate, and only through its to-date
 * when it has one.
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

    /** The first day the rate applies; null when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day the rate applies; null when it applies until the grade's next later-dated rate. */
    @Column(name = "to_date")
    private LocalDate toDate;

    /** How many versions the rate has had; this one, the newest, is the one in force. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * Set when the rate is forward-only: the highest claim id there was when it was saved; only a claim with a higher
     * stamp sees it. Null when it applies to every claim.
     */
    @Column(name = "forward_after")
    private Long forwardAfter;

    /** Its older versions, as far as they were read with it: what a claim that does not see this one falls back on. */
    @Transient
    private List<RateVersion> older = List.of();

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

    public List<RateVersion> getOlder() {
        return older;
    }

    public void setOlder(List<RateVersion> older) {
        this.older = older;
    }

    /** Whether a claim with the stamp (null: any claim, without regard to forward-only rates) sees a setting marked so. */
    public static boolean seen(Long forwardAfter, Long stamp) {
        return forwardAfter == null || stamp == null || stamp > forwardAfter;
    }

    /**
     * The rate as the claim with the stamp sees it (null: as it is): this one, else its newest older version the claim
     * sees; empty when it sees none of them.
     */
    public Optional<Rate> seenBy(Long stamp) {
        if (seen(forwardAfter, stamp)) {
            return Optional.of(this);
        }
        return older.stream()
                .sorted(Comparator.comparingInt(RateVersion::getVersion).reversed())
                .filter(v -> seen(v.getForwardAfter(), stamp))
                .findFirst()
                .map(v -> {
                    Rate rate = new Rate(sheetId, grade, fromDate, v.getPct());
                    rate.setToDate(v.getToDate());
                    return rate;
                });
    }

    /** Whether the rate applies on the date, given it is the grade's latest-dated rate on or before it. */
    public boolean appliesOn(LocalDate date) {
        return toDate == null || !date.isAfter(toDate);
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }
}
