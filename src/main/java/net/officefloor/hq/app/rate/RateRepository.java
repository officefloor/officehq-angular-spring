package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.person.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetIdOrderByGradeAscFromDateAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, Grade grade);

    /** The grade's rate with this from-date ({@code null}: the one with none). */
    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, Grade grade, LocalDate fromDate);

    /** The grade's rate in force on {@code date}: the one with the latest from-date on or before it. */
    default Optional<Rate> findInForce(String sheetId, Grade grade, LocalDate date) {
        return findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())));
    }
}
