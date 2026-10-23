package net.officefloor.hq.app.grade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** A grade retired on a sheet, and who retired it. */
@Entity
@Table(name = "grade_retired")
@IdClass(RetiredGrade.Key.class)
public class RetiredGrade {

    /** A sheet and a grade. */
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
    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Id
    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "retired_by", nullable = false)
    private String retiredBy;

    protected RetiredGrade() {
    }

    public RetiredGrade(String sheetId, String grade, String retiredBy) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.retiredBy = retiredBy;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getGrade() {
        return grade;
    }

    public String getRetiredBy() {
        return retiredBy;
    }
}
