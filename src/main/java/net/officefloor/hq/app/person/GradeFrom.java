package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** A grade a person has from a from-date (or from the beginning of time when it has none). */
@Embeddable
public class GradeFrom {

    /** Grades by from-date, the one with no from-date first. */
    public static final Comparator<GradeFrom> BY_FROM =
            Comparator.comparing(GradeFrom::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "grade_from")
    private LocalDate from;

    protected GradeFrom() {}

    public GradeFrom(String grade, LocalDate from) {
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
