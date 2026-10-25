package net.officefloor.hq.app.grade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetGradeRepository extends JpaRepository<SheetGrade, Long> {

    List<SheetGrade> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
