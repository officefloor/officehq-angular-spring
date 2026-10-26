package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A grade a person has from its from-date (null: from the beginning of time). */
@Embeddable
public class DatedGrade {

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate from;

    protected DatedGrade() {
    }

    public DatedGrade(String grade, LocalDate from) {
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
