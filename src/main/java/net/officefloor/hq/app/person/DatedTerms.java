package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One of a person's own terms: in force from its from-date until their next; null terms: none. */
@Embeddable
public class DatedTerms {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "terms")
    private Integer terms;

    protected DatedTerms() {
    }

    public DatedTerms(LocalDate fromDate, Integer terms) {
        this.fromDate = fromDate;
        this.terms = terms;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public Integer getTerms() {
        return terms;
    }
}
