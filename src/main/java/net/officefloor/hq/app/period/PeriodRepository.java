package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** A sheet's periods by from-date. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a period on the sheet shares any day with from..to (both inclusive). */
    boolean existsBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId, LocalDate to,
            LocalDate from);

    /** The period on the sheet containing the date (periods never overlap). */
    Optional<Period> findBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId, LocalDate date,
            LocalDate sameDate);

    /** A sheet's open periods by to-date. */
    List<Period> findBySheetIdAndClosedFalseOrderByToDateAsc(String sheetId);

    Optional<Period> findByIdAndSheetId(Long id, String sheetId);

    /** Whether a closed period on the sheet contains the date. */
    boolean existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId,
            LocalDate date, LocalDate sameDate);

    boolean existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId,
            LocalDate date, LocalDate sameDate);
}
