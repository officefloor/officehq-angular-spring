package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the value it held before a newer version replaced it. */
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

    @Column(name = "pct", nullable = false)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate toDate;

    /** Where in the order claims are added the version was saved when forward-only; null: every claim. */
    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateVersion() {
    }

    public RateVersion(Long rateId, int version, BigDecimal pct, LocalDate toDate, Long forwardAfter) {
        this.rateId = rateId;
        this.forwardAfter = forwardAfter;
        this.version = version;
        this.pct = pct;
        this.toDate = toDate;
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

    /** The last date the version applied on; null when it had no end. */
    public LocalDate getToDate() {
        return toDate;
    }

    /** Where the version was saved in the order claims are added when forward-only; else null. */
    public Long getForwardAfter() {
        return forwardAfter;
    }
}
