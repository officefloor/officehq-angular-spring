package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    /** The sheet's open periods whose to-date is before the date, by to-date. */
    List<Period> findBySheetIdAndClosedFalseAndToBeforeOrderByToAsc(String sheetId, LocalDate date);

    Optional<Period> findByIdAndSheetId(Long id, String sheetId);

    /** Whether a closed period of the sheet contains the date. */
    boolean existsBySheetIdAndClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(String sheetId, LocalDate date,
            LocalDate sameDate);

    /** Whether a soft-closed period of the sheet contains the date. */
    boolean existsBySheetIdAndSoftClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(String sheetId, LocalDate date,
            LocalDate sameDate);

    /** The period of the sheet that contains the date; periods never overlap, so there is at most one. */
    Optional<Period> findFirstBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(String sheetId, LocalDate date,
            LocalDate sameDate);

    boolean existsBySheetIdAndName(String sheetId, String name);

    /** Whether a period of the sheet shares a day with the dates from..to (inclusive). */
    boolean existsBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(String sheetId, LocalDate to, LocalDate from);
}
