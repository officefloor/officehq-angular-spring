package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods by from-date. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    /** The sheet's closed periods. */
    List<Period> findBySheetIdAndClosedTrue(String sheetId);

    /** The period on the sheet with this id, if any. */
    Optional<Period> findByIdAndSheetId(long id, String sheetId);

    /** The period on the sheet holding this name key, if any. */
    Optional<Period> findBySheetIdAndNameKey(String sheetId, String nameKey);
}
