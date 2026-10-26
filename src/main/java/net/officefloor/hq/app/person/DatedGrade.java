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

    /** The sheet's today when the grade was recorded; null: before that was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected DatedGrade() {
    }

    public DatedGrade(String grade, LocalDate from) {
        this(grade, from, null);
    }

    public DatedGrade(String grade, LocalDate from, LocalDate madeOn) {
        this.grade = grade;
        this.from = from;
        this.madeOn = madeOn;
    }

    /** Whether the grade was recorded while the sheet's today was on or before the day. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }
}
