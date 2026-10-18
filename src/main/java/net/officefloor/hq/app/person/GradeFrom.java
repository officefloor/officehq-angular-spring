package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;

/** A grade a person was given, from a date (none: from the beginning of time). */
@Embeddable
public class GradeFrom {

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    protected GradeFrom() {}

    public GradeFrom(Grade grade, LocalDate fromDate) {
        this.grade = grade;
        this.fromDate = fromDate;
    }

    public Grade getGrade() { return grade; }

    public LocalDate getFromDate() { return fromDate; }

    /** Whether it has taken over by {@code date}: from the beginning of time, or from that date or before. */
    public boolean appliesOn(LocalDate date) { return fromDate == null || !fromDate.isAfter(date); }
}
