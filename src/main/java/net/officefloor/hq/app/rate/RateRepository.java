package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates, by grade. */
    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate with this from-date; none given finds the one with no from-date. */
    default Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> from == null ? r.getFromDate() == null : from.equals(r.getFromDate()))
                .findFirst();
    }

    /** The grade's rate in force on a date: the one whose from-date is the latest on or before it,
     *  a rate with no from-date applying from the beginning of time; none when no rate applies, or
     *  when the date is after that rate's to-date. */
    default Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.coversThrough(date));
    }
}
