package net.officefloor.hq.app.grade;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetGradeRepository extends JpaRepository<SheetGrade, Long> {

    /** The grades every sheet has. */
    List<String> BUILT_IN = List.of("A", "B", "C");

    /** The grades added to a sheet, in the order they were added. */
    List<SheetGrade> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the grade was added to the sheet. */
    boolean existsBySheetIdAndGrade(String sheetId, String grade);

    /** Every grade of a sheet: A, B and C, then those added to it in the order they were added. */
    default List<String> gradesOf(String sheetId) {
        List<String> grades = new ArrayList<>(BUILT_IN);
        findBySheetIdOrderByIdAsc(sheetId).forEach(g -> grades.add(g.getGrade()));
        return grades;
    }

    /** Whether the grade is one of the sheet's. */
    default boolean isGradeOf(String sheetId, String grade) {
        return BUILT_IN.contains(grade) || existsBySheetIdAndGrade(sheetId, grade);
    }
}
