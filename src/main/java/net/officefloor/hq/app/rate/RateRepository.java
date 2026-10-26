package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetIdOrderByGradeAscFromAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFromIsNull(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from);

    /** The grade's rate for the from-date, null meaning the one with no from-date. */
    default Optional<Rate> findExact(String sheetId, String grade, LocalDate from) {
        return from == null ? findBySheetIdAndGradeAndFromIsNull(sheetId, grade)
                : findBySheetIdAndGradeAndFrom(sheetId, grade, from);
    }

    /**
     * The grade's rate in force on the date: the one with the latest from-date on or before it, unless the date is
     * past its to-date.
     */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return Optional.ofNullable(Rate.inForce(findBySheetIdAndGrade(sheetId, grade), date));
    }
}
