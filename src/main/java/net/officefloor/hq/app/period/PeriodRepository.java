package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods, by from-date. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    /** Whether a soft-closed period of the sheet contains the date. */
    boolean existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId,
            LocalDate onOrAfterFrom, LocalDate onOrBeforeTo);

    default boolean softClosedOn(String sheetId, LocalDate date) {
        return existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date);
    }

    Optional<Period> findByIdAndSheetId(long id, String sheetId);

    /** Whether a (fully) closed period of the sheet contains the date. */
    boolean existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(String sheetId,
            LocalDate onOrAfterFrom, LocalDate onOrBeforeTo);

    default boolean closedOn(String sheetId, LocalDate date) {
        return existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date);
    }
}
