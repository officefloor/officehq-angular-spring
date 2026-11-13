package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A person's own terms from a date; none when from that date they have no terms of their own. */
@Entity
@Table(name = "person_terms")
public class PersonTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Their terms in days; null for none of their own. */
    @Column(name = "terms")
    private Integer terms;

    /** The first day the terms apply. */
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    protected PersonTerms() {
    }

    PersonTerms(Integer terms, LocalDate fromDate) {
        this.terms = terms;
        this.fromDate = fromDate;
    }

    public Integer getTerms() {
        return terms;
    }

    void setTerms(Integer terms) {
        this.terms = terms;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }
}
