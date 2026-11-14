package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A person's own terms in force from a from-date (null terms: none from then); the person's undated
 *  terms apply from the beginning of time. */
@Entity
@Table(name = "person_terms")
public class PersonTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    protected PersonTerms() {
    }

    public PersonTerms(String sheetId, Long personId, Integer terms, LocalDate from) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.terms = terms;
        this.from = from;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    public LocalDate getFrom() {
        return from;
    }
}
