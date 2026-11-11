package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** A sheet's rates in grade order, each grade's undated rate first and then by from-date. */
    List<Rate> findBySheetIdOrderByGradeAscFromDateAscIdAsc(String sheetId);

    /** A grade's rates on a sheet. */
    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** The grade's rate with the from-date, or its undated rate when {@code fromDate} is null. */
    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);
}
