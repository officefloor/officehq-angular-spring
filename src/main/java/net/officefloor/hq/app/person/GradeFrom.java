package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A grade a person was given, from a date; no date: from the beginning of time. */
@Embeddable
public class GradeFrom {

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    protected GradeFrom() {
    }

    public GradeFrom(String grade, LocalDate fromDate) {
        this.grade = grade;
        this.fromDate = fromDate;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }
}
