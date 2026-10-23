package net.officefloor.hq.app.grade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A grade added to a sheet beside A, B and C, and who added it. */
@Entity
@Table(name = "grade_added")
public class AddedGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "added_by", nullable = false)
    private String addedBy;

    protected AddedGrade() {
    }

    public AddedGrade(String sheetId, String grade, String addedBy) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.addedBy = addedBy;
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

    public String getAddedBy() {
        return addedBy;
    }
}
