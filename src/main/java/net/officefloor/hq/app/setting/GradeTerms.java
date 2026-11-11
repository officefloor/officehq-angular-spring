package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A grade's own terms on a sheet: the days after its date a claim of a person of the grade is due by. */
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
