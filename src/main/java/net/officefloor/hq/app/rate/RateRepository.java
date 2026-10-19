package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates. */
    List<Rate> findBySheetId(String sheetId);

    /** A grade's rates on the sheet. */
    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    /** A grade's rate from a from-date; a null from-date finds the one with none. */
    Optional<Rate> findBySheetIdAndGradeAndFrom(String sheetId, String grade, LocalDate from);
}
