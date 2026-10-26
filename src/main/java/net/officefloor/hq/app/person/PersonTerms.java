package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A person's own terms, in days, in force from its from-date until their next dated terms; null terms
 *  are none, so their grade's (else the sheet's) are in force then. */
@Entity
@Table(name = "person_terms")
public class PersonTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    protected PersonTerms() {
    }

    PersonTerms(Integer terms, LocalDate from) {
        this.terms = terms;
        this.from = from;
    }

    public Integer getTerms() {
        return terms;
    }

    void setTerms(Integer terms) {
        this.terms = terms;
    }

    public LocalDate getFrom() {
        return from;
    }
}
