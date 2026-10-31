package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the percent and to-date it had as that version, before a newer one replaced it. */
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

    @Column(name = "pct", nullable = false, precision = 14, scale = 2)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate toDate;

    /** The claim id the version applied past when it was forward-only; null when it applied to every claim. */
    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateVersion() {
    }

    /** The rate's current version, kept as it stands before it is replaced. */
    public RateVersion(Rate rate) {
        this.rateId = rate.getId();
        this.version = rate.getVersion();
        this.pct = rate.getPct();
        this.toDate = rate.getToDate();
        this.forwardAfter = rate.getForwardAfter();
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

    public Long getForwardAfter() {
        return forwardAfter;
    }
}
