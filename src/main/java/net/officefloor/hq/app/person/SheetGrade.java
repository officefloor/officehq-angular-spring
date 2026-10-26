package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A grade added to the sheet, beyond the A, B and C every sheet has. */
@Entity
@Table(name = "sheet_grade")
public class SheetGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private Grade grade;

    protected SheetGrade() {
    }

    public SheetGrade(String sheetId, Grade grade) {
        this.sheetId = sheetId;
        this.grade = grade;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Grade getGrade() {
        return grade;
    }
}
