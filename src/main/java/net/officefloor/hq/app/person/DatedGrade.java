package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One of a person's grades: in force from its from-date (none: from the beginning of time) until their next. */
@Embeddable
public class DatedGrade {

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "grade", nullable = false)
    private String grade;

    protected DatedGrade() {
    }

    public DatedGrade(LocalDate fromDate, String grade) {
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
