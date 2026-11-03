package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A person's own terms recorded from a from-date, applying until a later from-date of the person
 *  takes over; terms of null mean none from then (their grade's or the sheet's apply). */
@Embeddable
public class TermsFrom {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "terms")
    private Integer terms;

    protected TermsFrom() {
    }

    public TermsFrom(LocalDate fromDate, Integer terms) {
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
