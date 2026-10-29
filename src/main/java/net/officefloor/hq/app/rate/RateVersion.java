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

    protected RateVersion() {
    }

    /** The rate's version as it is now, kept before it is replaced. */
    public RateVersion(Rate rate) {
        this.rateId = rate.getId();
        this.version = rate.getVersion();
        this.pct = rate.getPct();
        this.to = rate.getTo();
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
}
