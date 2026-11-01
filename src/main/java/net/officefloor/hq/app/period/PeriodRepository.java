package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    Optional<Period> findByIdAndSheetId(Long id, String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);

    /** Whether a closed period on the sheet contains the date. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.closed = true"
            + " and p.fromDate <= :date and p.toDate >= :date")
    boolean closedOn(String sheetId, LocalDate date);

    /** Whether a soft-closed period on the sheet contains the date. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.softClosed = true"
            + " and p.fromDate <= :date and p.toDate >= :date")
    boolean softClosedOn(String sheetId, LocalDate date);
}
