package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An older version of a rate: the percent, to-date and terms it had before a newer version replaced them. */
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

    /** The rate's terms while this version was its newest, as {@link Rate} keeps them; null when it
     * applied to every claim. */
    @Column(name = "terms", length = 4000)
    private String terms;

    protected RateVersion() {
    }

    public RateVersion(Long rateId, int version, BigDecimal pct, LocalDate to) {
        this.rateId = rateId;
        this.version = version;
        this.pct = pct;
        this.to = to;
    }

    public RateVersion(Long rateId, int version, BigDecimal pct, LocalDate to, String terms) {
        this(rateId, version, pct, to);
        this.terms = terms;
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

    public LocalDate getTo() {
        return to;
    }

    public String getTerms() {
        return terms;
    }
}
