package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** Terms a person has from a date until their next dated ones'; null terms are none of their own then. */
@Embeddable
public class TermsFrom {

    /** Orders dated terms by the date they take effect. */
    public static final Comparator<TermsFrom> BY_FROM = Comparator.comparing(TermsFrom::getFromDate);

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
