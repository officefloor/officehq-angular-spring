package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A grade (A, B or C) a person was given, in force from its from-date (null: from the beginning of
 *  time, as the first grade a person was given is). */
@Entity
@Table(name = "person_grade")
public class PersonGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate from;

    protected PersonGrade() {
    }

    public PersonGrade(String sheetId, Long personId, String grade, LocalDate from) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.grade = grade;
        this.from = from;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    /** Whether the grade is in force on a date: its from-date is none or on or before the date. */
    public boolean appliesOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }
}
