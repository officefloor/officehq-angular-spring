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

    /** The sheet's today when the grade was given; null when given before this was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected PersonGrade() {
    }

    PersonGrade(Grade grade, LocalDate from, LocalDate madeOn) {
        this.grade = grade;
        this.from = from;
        this.madeOn = madeOn;
    }

    public Grade getGrade() {
        return grade;
    }

    void setGrade(Grade grade, LocalDate madeOn) {
        this.grade = grade;
        this.madeOn = madeOn;
    }

    /** Whether the grade was given while the sheet's today was on or before the date. */
    boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
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
