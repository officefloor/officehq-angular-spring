package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetIdOrderByGradeAscFromDateAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFromDateIsNull(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);
}
