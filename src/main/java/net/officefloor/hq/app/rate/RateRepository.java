package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetId(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);
}
