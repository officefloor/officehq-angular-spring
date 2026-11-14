package net.officefloor.hq.app.grade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetGradeRepository extends JpaRepository<SheetGrade, Long> {

    /** The sheet's added grades, in the order they were added. */
    List<SheetGrade> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the grade is added on the sheet. */
    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
