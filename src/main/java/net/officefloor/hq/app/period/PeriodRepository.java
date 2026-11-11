package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** A sheet's periods in date order. */
    List<Period> findBySheetIdOrderByFromDateAscIdAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
