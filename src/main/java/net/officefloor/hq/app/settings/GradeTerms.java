package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The terms set for one grade on a sheet; a grade with none uses the sheet's terms. */
@Entity
@Table(name = "grade_terms")
public class GradeTerms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "terms", nullable = false)
    private int terms;

    protected GradeTerms() {
    }

    public GradeTerms(String sheetId, String grade, int terms) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.terms = terms;
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

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }
}
