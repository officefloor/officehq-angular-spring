package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods, the earliest first. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    /** Whether a period on the sheet has this name, ignoring case. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and lower(p.name) = lower(:name)")
    boolean nameInUse(@Param("sheetId") String sheetId, @Param("name") String name);

    /** The sheet's period by name. */
    Optional<Period> findBySheetIdAndName(String sheetId, String name);

    /** Whether a closed period on the sheet contains the date. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.closed = true"
            + " and p.from <= :date and p.to >= :date")
    boolean closedOn(@Param("sheetId") String sheetId, @Param("date") LocalDate date);

    /** Whether a soft-closed period on the sheet contains the date. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.softClosed = true"
            + " and p.from <= :date and p.to >= :date")
    boolean softClosedOn(@Param("sheetId") String sheetId, @Param("date") LocalDate date);
}
