package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetiredGradeRepository extends JpaRepository<RetiredGrade, Long> {

    List<RetiredGrade> findBySheetId(String sheetId);

    boolean existsBySheetIdAndGrade(String sheetId, String grade);
}
