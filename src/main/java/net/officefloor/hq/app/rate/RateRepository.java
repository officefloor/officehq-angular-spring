package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<Rate, Long> {

    /** The sheet's rates, by grade. */
    List<Rate> findBySheetIdOrderByGradeAsc(String sheetId);

    Optional<Rate> findBySheetIdAndGrade(String sheetId, String grade);
}
