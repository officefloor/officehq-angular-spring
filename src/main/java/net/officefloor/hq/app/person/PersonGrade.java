package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A grade a person was given, from a date (or from the beginning of time when it has none). */
@Entity
@Table(name = "person_grade")
public class PersonGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The first day the grade applies; none when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    protected PersonGrade() {
    }

    PersonGrade(String grade, LocalDate fromDate) {
        this.grade = grade;
        this.fromDate = fromDate;
    }

    public String getGrade() {
        return grade;
    }

    void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }
}
