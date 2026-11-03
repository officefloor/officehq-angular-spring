package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the percent and to-date a rate had before a later setting for the
 *  same grade and from-date replaced them. */
@Entity
@Table(name = "rate_version")
public class RateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rate_id", nullable = false)
    private Long rateId;

    /** Which version of the rate this was, from 1. */
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateVersion() {
    }

    /** The rate as it is now, kept as an older version. */
    public RateVersion(Rate rate) {
        this.rateId = rate.getId();
        this.version = rate.getVersion();
        this.pct = rate.getPct();
        this.toDate = rate.getToDate();
        this.forwardAfter = rate.getForwardAfter();
    }

    /** The rate as this version had it, detached: for working out figures only, never saved. */
    public Rate toRate(Rate rate) {
        Rate was = new Rate(rate.getSheetId(), rate.getGrade(), pct, rate.getFromDate());
        was.setToDate(toDate);
        was.setForwardAfter(forwardAfter);
        return was;
    }

    public int getVersion() {
        return version;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public Long getForwardAfter() {
        return forwardAfter;
    }
}
