package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLogRepository extends JpaRepository<RateLog, Long> {

    /** The rates set on the sheet, in the order they were set. */
    List<RateLog> findBySheetIdOrderByIdAsc(String sheetId);
}
