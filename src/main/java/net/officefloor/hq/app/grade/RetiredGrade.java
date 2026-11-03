package net.officefloor.hq.app.grade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A grade retired on a sheet: it can no longer be chosen for a person; those on it keep it. */
@Entity
@Table(name = "retired_grade")
public class RetiredGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(nullable = false)
    private String grade;

    /** The sheet's today when the grade was retired. */
    @Column(name = "retired_on", nullable = false)
    private LocalDate retiredOn;

    protected RetiredGrade() {
    }

    public RetiredGrade(String sheetId, String grade, LocalDate retiredOn) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.retiredOn = retiredOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getRetiredOn() {
        return retiredOn;
    }
}
