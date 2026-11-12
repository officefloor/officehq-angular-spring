package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A person's own terms in force from its from-date until the person's next later-dated terms; without terms, the
 * person has none of their own from that date (their grade's or the sheet's apply).
 */
@Entity
@Table(name = "person_terms")
public class PersonTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The terms in whole days; null for none. */
    @Column(name = "terms")
    private Integer terms;

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
