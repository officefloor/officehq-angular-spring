package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the value (and to-date) a grade's rate from a from-date had before it was set again. */
@Entity
@Table(name = "rate_version")
public class RateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rate_id", nullable = false)
    private Long rateId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "claims_after")
    private Long claimsAfter;

    protected RateVersion() {
    }

    public RateVersion(Long rateId, int version, BigDecimal rate, LocalDate toDate, Long claimsAfter) {
        this.rateId = rateId;
        this.version = version;
        this.rate = rate;
        this.toDate = toDate;
        this.claimsAfter = claimsAfter;
    }

    public Long getId() {
        return id;
    }

    public Long getRateId() {
        return rateId;
    }

    public int getVersion() {
        return version;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** For a forward-only version, the highest claim id there was when it was saved: it applies only to claims
     *  after it. Null when it applies to every claim. */
    public Long getClaimsAfter() {
        return claimsAfter;
    }
}
