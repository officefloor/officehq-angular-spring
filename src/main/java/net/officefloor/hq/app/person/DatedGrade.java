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

    /** The sheet's today when it was recorded; none: from the beginning of time. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected DatedGrade() {
    }

    public DatedGrade(LocalDate fromDate, String grade, LocalDate madeOn) {
        this.fromDate = fromDate;
        this.grade = grade;
        this.madeOn = madeOn;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether it was recorded by the end of a day: the sheet's today then on or before it. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
