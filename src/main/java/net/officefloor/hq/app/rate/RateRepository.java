package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The rates on a sheet, by grade and then from-date, the one with no from-date first. */
    @Query("select r from Rate r where r.sheetId = :sheetId order by r.grade asc, r.fromDate asc nulls first")
    List<Rate> findBySheetIdOrdered(String sheetId);

    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);

    Optional<Rate> findBySheetIdAndGradeAndFromDateIsNull(String sheetId, String grade);

    /** The rates of a grade on a sheet, the latest from-date first and the one with none last. */
    @Query("select r from Rate r where r.sheetId = :sheetId and r.grade = :grade order by r.fromDate desc nulls last")
    List<Rate> findLatestFirst(String sheetId, String grade);

    /** The rate of a grade in force on a day: the one with the latest from-date on or before it, or
     *  else the one with no from-date; empty when the day is before every rate of the grade. */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate on) {
        return findLatestFirst(sheetId, grade).stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(on))
                .findFirst();
    }
}
