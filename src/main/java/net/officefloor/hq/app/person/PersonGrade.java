package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A grade recorded for a person, in force from its from-date (or from the beginning of time without one). */
@Embeddable
public class PersonGrade {

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "grade", nullable = false)
    private String grade;

    protected PersonGrade() {
    }

    public PersonGrade(LocalDate from, String grade) {
        this.from = from;
        this.grade = grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    public String getGrade() {
        return grade;
    }
}
