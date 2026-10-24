package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A grade a person was given, in force from {@code from}; a null from-date is the beginning of time. */
@Embeddable
public class PersonGrade {

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "grade_from")
    private LocalDate from;

    protected PersonGrade() {
    }

    public PersonGrade(String grade, LocalDate from) {
        this.grade = grade;
        this.from = from;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }
}
