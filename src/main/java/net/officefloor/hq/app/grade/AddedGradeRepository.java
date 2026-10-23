package net.officefloor.hq.app.grade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddedGradeRepository extends JpaRepository<AddedGrade, Long> {

    /** The grades added to the sheet, in the order they were added. */
    List<AddedGrade> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the grade was added to the sheet. */
    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
