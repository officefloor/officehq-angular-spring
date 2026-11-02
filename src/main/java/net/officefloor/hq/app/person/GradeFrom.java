package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A grade a person was given, applying from its from-date (from the beginning of time when it has
 *  none) until a later from-date of the person takes over. */
@Embeddable
public class GradeFrom {

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "grade", nullable = false)
    private String grade;

    protected GradeFrom() {
    }

    public GradeFrom(LocalDate fromDate, String grade) {
        this.fromDate = fromDate;
        this.grade = grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public String getGrade() {
        return grade;
    }
}
