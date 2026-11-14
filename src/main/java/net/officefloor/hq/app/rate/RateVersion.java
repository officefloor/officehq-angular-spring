package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the value and to-date the rate held as that version, kept when a newer
 *  one replaced it, with the sheet's today and the user then. */
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

    @Column(name = "rate", nullable = false, precision = 7, scale = 2)
    private BigDecimal rate;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "replaced_on")
    private LocalDate replacedOn;

    @Column(name = "replaced_by", nullable = false)
    private String replacedBy;

    protected RateVersion() {
    }

    /** The rate's current version, as it stands before it is replaced. */
    public RateVersion(Rate replaced, LocalDate replacedOn, String replacedBy) {
        this.rateId = replaced.getId();
        this.version = replaced.getVersion();
        this.rate = replaced.getRate();
        this.to = replaced.getTo();
        this.replacedOn = replacedOn;
        this.replacedBy = replacedBy;
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

    public LocalDate getTo() {
        return to;
    }

    public LocalDate getReplacedOn() {
        return replacedOn;
    }

    public String getReplacedBy() {
        return replacedBy;
    }
}
