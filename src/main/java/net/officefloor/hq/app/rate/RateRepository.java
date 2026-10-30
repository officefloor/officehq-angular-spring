package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetId(String sheetId);

    /** The grade's rate with this from-date; a null from-date finds the one that has none. */
    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);
}
