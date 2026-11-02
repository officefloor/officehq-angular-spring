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
     *  else the one with no from-date; empty when the day is before every rate of the grade, or
     *  after the to-date of that rate. */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate on) {
        return inForce(findLatestFirst(sheetId, grade), on);
    }

    /** The rate of a grade in force on a day on the first of some sheets (a sheet's ancestors,
     *  nearest first) that has one in force on it; empty when none has. */
    default Optional<Rate> findInForceUp(List<String> sheetIds, String grade, LocalDate on) {
        for (String sheetId : sheetIds) {
            Optional<Rate> rate = findInForce(sheetId, grade, on);
            if (rate.isPresent()) {
                return rate;
            }
        }
        return Optional.empty();
    }

    /** Whether the rate of a grade that would apply on a day (the one with the latest from-date on
     *  or before it, or else the one with no from-date) has ended before it. */
    default boolean endedBefore(String sheetId, String grade, LocalDate on) {
        return endedBefore(findLatestFirst(sheetId, grade), on);
    }

    /** The rate in force on a day among a grade's rates given latest from-date first (see
     *  {@link #findInForce}). */
    static Optional<Rate> inForce(List<Rate> latestFirst, LocalDate on) {
        return latestFirst.stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(on))
                .findFirst()
                .filter(r -> r.getToDate() == null || !r.getToDate().isBefore(on));
    }

    /** Whether, among a grade's rates given latest from-date first, the one that would apply on a day
     *  has ended before it (see {@link #endedBefore(String, String, LocalDate)}). */
    static boolean endedBefore(List<Rate> latestFirst, LocalDate on) {
        return latestFirst.stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(on))
                .findFirst()
                .map(r -> r.getToDate() != null && r.getToDate().isBefore(on))
                .orElse(false);
    }
}
