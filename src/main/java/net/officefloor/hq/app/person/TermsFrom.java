package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A person's own terms (null: none), from a date. */
@Embeddable
public class TermsFrom {

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    protected TermsFrom() {
    }

    public TermsFrom(Integer terms, LocalDate fromDate) {
        this.terms = terms;
        this.fromDate = fromDate;
    }

    public Integer getTerms() {
        return terms;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }
}
