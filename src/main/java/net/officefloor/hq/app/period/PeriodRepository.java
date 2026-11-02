package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The periods on a sheet, earliest first. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    /** Whether the name is in use on the sheet, compared without regard to case. */
    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a period on the sheet shares a day with the days from one date to another. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.fromDate <= :to and p.toDate >= :from")
    boolean overlaps(String sheetId, LocalDate from, LocalDate to);
}
