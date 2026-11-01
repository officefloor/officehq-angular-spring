package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * The sheet's rate for one grade from a date: a percent with two decimals (0.00 or more). A rate
 * with no from-date applies from the beginning of time; one with a to-date applies through it. Setting
 * a grade and from-date again adds a new version; the row holds the newest, the one in force.
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

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "versions", nullable = false)
    private int versions = 1;

    protected Rate() {}

    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.pct = pct;
    }

    /** The rate as it stood after the given number of versions. */
    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal pct, int versions) {
        this(sheetId, grade, fromDate, toDate, pct);
        this.versions = versions;
    }

    /**
     * Of one grade's rates, the one in force on the date: the one with the latest from-date on or
     * before it, unless the date is after that rate's to-date (then none).
     */
    public static Optional<Rate> inForce(Collection<Rate> rates, LocalDate date) {
        return rates.stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(rate -> rate.toDate == null || !date.isAfter(rate.toDate));
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getGrade() { return grade; }

    /** The first date it applies on; null when it applies from the beginning of time. */
    public LocalDate getFromDate() { return fromDate; }

    /** Whether it applies on the date: it has no from-date, or one on or before the date. */
    public boolean appliesOn(LocalDate date) { return fromDate == null || !fromDate.isAfter(date); }

    /** The last date it applies on; null when it has no end. */
    public LocalDate getToDate() { return toDate; }

    public BigDecimal getPct() { return pct; }

    /** How many times its grade and from-date have been set; this is the newest version, the older ones are in the rate log. */
    public int getVersions() { return versions; }

    /** Keep the current value as an older version and make the percent and to-date the newest one. */
    public void addVersion(BigDecimal pct, LocalDate toDate) {
        this.versions++;
        this.pct = pct;
        this.toDate = toDate;
    }
}
