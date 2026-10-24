package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A person's own terms in force from {@code from}; null terms are none of their own from then. */
@Embeddable
public class PersonTerms {

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "terms_from", nullable = false)
    private LocalDate from;

    protected PersonTerms() {
    }

    public PersonTerms(Integer terms, LocalDate from) {
        this.terms = terms;
        this.from = from;
    }

    public Integer getTerms() {
        return terms;
    }

    public LocalDate getFrom() {
        return from;
    }
}
