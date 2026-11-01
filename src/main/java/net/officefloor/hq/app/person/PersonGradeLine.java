package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A line of a person's grade log: a grade recorded from a from-date, on the sheet's today then (null: before this was kept). */
@Embeddable
public class PersonGradeLine {

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "set_on")
    private LocalDate setOn;

    protected PersonGradeLine() {}

    public PersonGradeLine(LocalDate fromDate, String grade, LocalDate setOn) {
        this.fromDate = fromDate;
        this.grade = grade;
        this.setOn = setOn;
    }

    public LocalDate getFromDate() { return fromDate; }

    public String getGrade() { return grade; }

    /** Whether it was recorded on or before the date. */
    public boolean recordedBy(LocalDate date) { return setOn == null || !setOn.isAfter(date); }
}
