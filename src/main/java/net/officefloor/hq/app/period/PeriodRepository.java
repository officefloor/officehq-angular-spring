package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods in date order. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);
}
