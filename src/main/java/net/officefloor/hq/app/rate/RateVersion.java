package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the percent and to-date it had before a later setting replaced them. */
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

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected RateVersion() {
    }

    /** The rate as it stands now, kept as an older version. */
    public RateVersion(Rate rate) {
        this(rate.getId(), rate.getVersion(), rate.getPct(), rate.getToDate());
    }

    private RateVersion(Long rateId, int version, BigDecimal pct, LocalDate toDate) {
        this.rateId = rateId;
        this.version = version;
        this.pct = pct;
        this.toDate = toDate;
    }

    /** A copy of the version for another rate. */
    public RateVersion copyTo(Long rateId) {
        return new RateVersion(rateId, version, pct, toDate);
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

    public BigDecimal getPct() {
        return pct;
    }

    public LocalDate getToDate() {
        return toDate;
    }
}
