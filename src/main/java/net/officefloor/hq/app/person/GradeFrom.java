package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** A grade a person has from a date until their next one's; a null from-date is from the beginning of time. */
@Embeddable
public class GradeFrom {

    /** Orders grades by the date they take effect, the one from the beginning of time first. */
    public static final Comparator<GradeFrom> BY_FROM =
            Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder()));

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
