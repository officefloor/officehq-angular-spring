package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods in date order. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    Optional<Period> findByIdAndSheetId(Long id, String sheetId);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);

    /** Whether the date falls within one of the sheet's closed periods. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.closed = true"
            + " and p.from <= :date and p.to >= :date")
    boolean isClosedOn(@Param("sheetId") String sheetId, @Param("date") LocalDate date);
}
