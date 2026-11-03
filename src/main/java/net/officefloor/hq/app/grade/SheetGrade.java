package net.officefloor.hq.app.grade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A grade added to a sheet beside A, B and C: a code of one to three capital letters. */
@Entity
@Table(name = "sheet_grade")
public class SheetGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(nullable = false)
    private String grade;

    /** The sheet's today when the grade was added. */
    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    protected SheetGrade() {
    }

    public SheetGrade(String sheetId, String grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.addedOn = addedOn;
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

    public LocalDate getAddedOn() {
        return addedOn;
    }
}
