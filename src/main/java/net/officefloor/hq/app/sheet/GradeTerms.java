package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** The terms of a grade on a sheet: the days after its date a claim of a person of the grade is due
 *  by. A grade with none takes the sheet's terms. */
@Entity
@Table(name = "grade_terms")
@IdClass(GradeTerms.Key.class)
public class GradeTerms {

    /** The key of a grade's terms: its sheet and grade. */
    public static class Key implements Serializable {
        private String sheetId;
        private String grade;

        protected Key() {
        }

        public Key(String sheetId, String grade) {
            this.sheetId = sheetId;
            this.grade = grade;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sheetId, k.sheetId) && Objects.equals(grade, k.grade);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, grade);
        }
    }

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "grade")
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
