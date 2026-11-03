package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** A sheet's periods by from-date. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a period on the sheet shares any day with from..to (both inclusive). */
    boolean existsBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId, LocalDate to,
            LocalDate from);
}
