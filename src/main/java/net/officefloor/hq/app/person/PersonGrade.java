package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A grade a person was given, in force from its from-date (or from the beginning of time, when it
 *  has none) until the person's next grade. */
@Entity
@Table(name = "person_grade")
public class PersonGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "from_date")
    private LocalDate from;

    protected PersonGrade() {
    }

    PersonGrade(Grade grade, LocalDate from) {
        this.grade = grade;
        this.from = from;
    }

    public Grade getGrade() {
        return grade;
    }

    void setGrade(Grade grade) {
        this.grade = grade;
    }

    /** The date the grade is in force from, or null when it applies from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    /** Whether the grade is in force on or before this date. */
    boolean appliesBy(LocalDate date) {
        return from == null || !from.isAfter(date);
    }

    /** Whether this grade starts later than the other. */
    boolean startsAfter(PersonGrade other) {
        return other.from == null ? from != null : from != null && from.isAfter(other.from);
    }
}
