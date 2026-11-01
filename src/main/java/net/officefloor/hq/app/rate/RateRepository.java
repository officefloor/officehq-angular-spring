package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetIdOrderByGradeAscFromDateAsc(String sheetId);

    List<Rate> findBySheetIdAndGrade(String sheetId, String grade);

    Optional<Rate> findBySheetIdAndGradeAndFromDateIsNull(String sheetId, String grade);

    /** The last place given in the order claims were added: a claim added from now on comes after it. */
    @Query(value = "select next value for claim_added_seq", nativeQuery = true)
    long lastClaimAdded();

    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);
}
