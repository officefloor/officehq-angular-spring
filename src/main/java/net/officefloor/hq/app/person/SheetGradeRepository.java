package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetGradeRepository extends JpaRepository<SheetGrade, Long> {

    List<SheetGrade> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndGrade(String sheetId, Grade grade);

    /** The sheet's grades in order: A, B and C, then those added to it in the order added. */
    default List<Grade> gradesOf(String sheetId) {
        List<Grade> grades = new ArrayList<>(Grade.BUILT_IN);
        findBySheetIdOrderByIdAsc(sheetId).forEach((g) -> grades.add(g.getGrade()));
        return grades;
    }

    /** Whether the grade is one of the sheet's. */
    default boolean isGradeOf(String sheetId, Grade grade) {
        return Grade.BUILT_IN.contains(grade) || existsBySheetIdAndGrade(sheetId, grade);
    }
}
