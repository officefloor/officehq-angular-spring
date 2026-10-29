package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the percent and to-date it had before a newer version replaced them. */
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

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "to_date")
    private LocalDate to;

    /** The claim mark the version was saved at, for a forward-only one; null for one applying to every claim. */
    @Column(name = "after_mark")
    private Long afterMark;

    protected RateVersion() {
    }

    /** The rate's version as it is now, kept before it is replaced. */
    public RateVersion(Rate rate) {
        this.rateId = rate.getId();
        this.version = rate.getVersion();
        this.pct = rate.getPct();
        this.to = rate.getTo();
        this.afterMark = rate.getAfterMark();
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

    public LocalDate getTo() {
        return to;
    }

    public Long getAfterMark() {
        return afterMark;
    }
}
