package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A grade of a person, in force from its from-date (or from the beginning of time when it has none) until the
 * person's next later-dated grade.
 */
@Entity
@Table(name = "person_grade")
public class PersonGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The date the grade applies from; null for the beginning of time. */
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
