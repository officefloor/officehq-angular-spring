package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates by grade, each grade's from the earliest (no from-date first). */
    List<Rate> findBySheetIdOrderByGradeAscFromAscIdAsc(String sheetId);

    /** The grade's rates; no from-date first, then by from-date. */
    List<Rate> findBySheetIdAndGradeOrderByFromAsc(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from);

    Optional<Rate> findBySheetIdAndGradeAndFromIsNull(String sheetId, String grade);
}
