package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.person.Grade;

/** A setting of one of the sheet's rates, kept with the sheet's today it was made on, so the rates can
 *  be told as they stood at the end of any date. */
@Entity
@Table(name = "rate_change")
public class RateChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    /** The sheet's today when the rate was set; null when it was set before this was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    /** The version of the rate this change made; null when made before versions were kept. */
    @Column(name = "version")
    private Integer version;

    /** For a forward-only version, the highest claim id there was when it was saved; null when it applies
     *  to every claim. */
    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateChange() {
    }

    public RateChange(Rate rate, LocalDate madeOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.from = rate.getFrom();
        this.to = rate.getTo();
        this.pct = rate.getPct();
        this.madeOn = madeOn;
        this.version = rate.getVersion();
        this.forwardAfter = rate.getForwardAfter();
    }

    public Grade getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public Long getForwardAfter() {
        return forwardAfter;
    }

    /** Whether the version this change made applies to the claim (see {@link Rate#appliesToClaim}). */
    public boolean appliesToClaim(Long claimId) {
        return Rate.appliesToClaim(forwardAfter, claimId);
    }

    /** Whether the change was made while the sheet's today was on or before the date. */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    /** The rate as this change set it, not kept. */
    Rate toRate() {
        return new Rate(sheetId, grade, from, to, pct, forwardAfter);
    }
}
