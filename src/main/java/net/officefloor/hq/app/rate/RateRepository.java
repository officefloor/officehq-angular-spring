package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** A sheet's rates by grade, then by from-date. */
    List<Rate> findBySheetIdOrderByGradeAscFromDateAsc(String sheetId);

    /** A grade's rates on a sheet. */
    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate from a from-date, or its undated rate when the from-date is null. */
    default Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate fromDate) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFromDate(), fromDate)).findFirst();
    }

    /**
     * The grade's rate in force on a date: the one with the latest from-date on or before it, an
     * undated rate counting as from the beginning of time; empty when none applies yet.
     */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max((a, b) -> a.getFromDate() == null ? -1 : b.getFromDate() == null ? 1
                        : a.getFromDate().compareTo(b.getFromDate()));
    }
}
