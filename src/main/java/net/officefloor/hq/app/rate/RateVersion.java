package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the value (and to-date) a later setting of the same grade and from-date
 *  replaced. */
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

    /** Who set the replacing version. */
    @Column(name = "set_by")
    private String setBy;

    protected RateVersion() {
    }

    /** The version a rate is in now, kept as it is replaced by a user. */
    public RateVersion(Rate replaced, String setBy) {
        this.rateId = replaced.getId();
        this.version = replaced.getVersion();
        this.rate = replaced.getRate();
        this.toDate = replaced.getToDate();
        this.setBy = setBy;
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

    public String getSetBy() {
        return setBy;
    }
}
