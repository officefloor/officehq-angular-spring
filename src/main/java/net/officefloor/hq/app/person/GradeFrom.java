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

    /** The sheet's today when the grade was recorded; none when not kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected GradeFrom() {}

    public GradeFrom(String grade, LocalDate from, LocalDate madeOn) {
        this.grade = grade;
        this.from = from;
        this.madeOn = madeOn;
    }

    /** Whether the grade was recorded while the sheet's today was on or before a date (one not kept was). */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }
}
