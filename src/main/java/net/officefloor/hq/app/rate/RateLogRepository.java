package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLogRepository extends JpaRepository<RateLog, Long> {

    /** A grade's rate settings on a sheet, in the order they were made. */
    List<RateLog> findBySheetIdAndGradeOrderByIdAsc(String sheetId, String grade);
}
