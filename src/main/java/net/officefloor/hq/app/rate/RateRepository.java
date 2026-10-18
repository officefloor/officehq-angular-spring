package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.person.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);

    Optional<Rate> findBySheetIdAndGrade(String sheetId, Grade grade);
}
