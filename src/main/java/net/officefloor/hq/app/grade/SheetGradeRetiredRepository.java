package net.officefloor.hq.app.grade;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetGradeRetiredRepository extends JpaRepository<SheetGradeRetired, Long> {

    List<SheetGradeRetired> findBySheetIdOrderByGradeAsc(String sheetId);

    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
