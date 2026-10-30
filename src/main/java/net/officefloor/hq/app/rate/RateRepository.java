package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RateRepository extends JpaRepository<Rate, Long> {

    List<Rate> findBySheetId(String sheetId);

    /** The grade's rate with this from-date; a null from-date finds the one that has none. */
    Optional<Rate> findBySheetIdAndGradeAndFromDate(String sheetId, String grade, LocalDate fromDate);

    /** The highest claim id in the store, on any sheet; 0 when there is none. Claims added later have higher ids. */
    @Query(value = "select coalesce(max(id), 0) from claim", nativeQuery = true)
    long maxClaimId();
}
