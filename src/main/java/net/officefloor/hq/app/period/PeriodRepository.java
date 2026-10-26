package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);

    /** Whether a period of the sheet shares a day with the dates from..to (inclusive). */
    boolean existsBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(String sheetId, LocalDate to, LocalDate from);
}
