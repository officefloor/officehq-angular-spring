package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A snapshot line of a person: written with each SNAPSHOT for them, when the sheet's today is changed, with the day
 * being left and their due-total on it (null: n/a). The id orders a person's lines, oldest first;
 * they go with the person.
 */
@Entity
@Table(name = "person_snapshot")
public class PersonSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "left_day", nullable = false)
    private LocalDate day;

    @Column(name = "due_total")
    private BigDecimal dueTotal;

    protected PersonSnapshot() {
    }

    public PersonSnapshot(Long personId, LocalDate day, BigDecimal dueTotal) {
        this.personId = personId;
        this.day = day;
        this.dueTotal = dueTotal;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getDay() {
        return day;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }
}
