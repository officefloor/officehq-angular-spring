package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    Optional<Period> findBySheetIdAndName(String sheetId, String name);

    /** Whether a closed period of the sheet contains the date. */
    boolean existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            String sheetId, LocalDate from, LocalDate to);
}
