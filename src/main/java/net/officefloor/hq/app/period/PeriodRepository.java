package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** A sheet's periods in date order. */
    List<Period> findBySheetIdOrderByFromDateAscIdAsc(String sheetId);

    Optional<Period> findBySheetIdAndName(String sheetId, String name);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
