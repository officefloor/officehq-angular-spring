package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.HistoryListener;

/** A sheet's rate for a grade: a percent with two decimals (0.00 or more), in force from its
 *  from-date (null: from the beginning of time) through its to-date (null: with no end). */
@Entity
@Table(name = "rate")
@EntityListeners(HistoryListener.class)
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "rate", nullable = false, precision = 7, scale = 2)
    private BigDecimal rate;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "forward_after")
    private Long forwardAfter;

    /** The rate's older versions, newest first, when they have been read; null when not. */
    @Transient
    private List<Rate> earlier;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
        this.rate = rate;
    }

    /** A rate as it stood with a given version count, read back from the sheet's history. */
    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal rate, int version) {
        this(sheetId, grade, from, to, rate);
        this.version = version;
    }

    /** A rate as it stood with a given version count and forward-only mark, read back from the sheet's
     *  history. */
    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal rate, int version,
            Long forwardAfter) {
        this(sheetId, grade, from, to, rate, version);
        this.forwardAfter = forwardAfter;
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

    public LocalDate getFrom() {
        return from;
    }

    /** The last date the rate applies on; null when it has no end. */
    public LocalDate getTo() {
        return to;
    }

    /** Whether the rate has ended by a date: it has a to-date before the date. */
    public boolean endedBy(LocalDate date) {
        return to != null && to.isBefore(date);
    }

    /** Whether the rate is in force on a date: its from-date is none or on or before the date. */
    public boolean appliesOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }

    public BigDecimal getRate() {
        return rate;
    }

    /** How many versions the rate has: 1 for one never replaced; the value in force is the newest. */
    public int getVersion() {
        return version;
    }

    /** For a forward-only rate, the id of the newest claim there was when it was saved: it applies
     *  only to claims with a higher id. Null when it applies to every claim. */
    public Long getForwardAfter() {
        return forwardAfter;
    }

    public void setForwardAfter(Long forwardAfter) {
        this.forwardAfter = forwardAfter;
    }

    /** Whether the rate applies to a claim (null: no claim in particular): it is not forward-only, or
     *  the claim was added after it was saved. */
    public boolean appliesTo(Long claimId) {
        return appliesTo(forwardAfter, claimId);
    }

    static boolean appliesTo(Long forwardAfter, Long claimId) {
        return forwardAfter == null || claimId == null || claimId > forwardAfter;
    }

    /** The rate's older versions, newest first, as they were read with it; null when they were not. */
    public List<Rate> getEarlier() {
        return earlier;
    }

    public void setEarlier(List<Rate> earlier) {
        this.earlier = earlier;
    }

    /** This rate with another value, to-date and forward-only mark, as an older version held them. */
    Rate asVersion(BigDecimal rate, LocalDate to, int version, Long forwardAfter) {
        return new Rate(sheetId, grade, from, to, rate, version, forwardAfter);
    }

    /** Make a new value, to-date and forward-only mark (null: applies to every claim) the newest
     *  version, the one in force. */
    public void newVersion(BigDecimal rate, LocalDate to, Long forwardAfter) {
        this.rate = rate;
        this.to = to;
        this.forwardAfter = forwardAfter;
        this.version++;
    }
}
