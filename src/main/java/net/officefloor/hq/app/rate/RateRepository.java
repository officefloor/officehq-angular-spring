package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates, by grade, then by from-date (one with none first). */
    @Query("SELECT r FROM Rate r WHERE r.sheetId = :sheetId ORDER BY r.grade ASC, r.from ASC NULLS FIRST")
    List<Rate> findBySheetIdOrderByGradeAndFrom(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    @Query("SELECT r FROM Rate r WHERE r.sheetId = :sheetId AND r.grade = :grade AND r.from IS NULL")
    Optional<Rate> findUndated(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from);

    /** The grade's rate in force on {@code date}: the one with the latest from-date on or before it, or
     * the one with no from-date when none has; empty when no rate applies yet. */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.getFrom() == null || !r.getFrom().isAfter(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }
}
