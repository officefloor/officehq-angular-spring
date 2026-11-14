package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates, by grade. */
    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate for a from-date (null: the undated rate). */
    default Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFrom(), from))
                .findFirst();
    }

    /** The grade's rate in force on a date: the one with the latest from-date on or before it
     *  (an undated rate applying from the beginning of time); none when no rate applies yet, or when
     *  that one's to-date has passed by then. */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate on) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.runsThrough(on));
    }
}
