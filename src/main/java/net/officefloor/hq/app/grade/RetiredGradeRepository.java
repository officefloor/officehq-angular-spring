package net.officefloor.hq.app.grade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetiredGradeRepository extends JpaRepository<RetiredGrade, RetiredGrade.Key> {

    /** The grades retired on the sheet. */
    List<RetiredGrade> findBySheetId(String sheetId);

    /** Whether the grade is retired on the sheet. */
    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
