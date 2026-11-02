package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /** The holidays of a sheet, earliest day first. */
    List<Holiday> findBySheetIdOrderByDayAsc(String sheetId);

    /** Whether the day is already a holiday of the sheet. */
    boolean existsBySheetIdAndDay(String sheetId, LocalDate day);
}
