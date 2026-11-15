package net.officefloor.hq.app.settings;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetHolidayRepository extends JpaRepository<SheetHoliday, Long> {

    /** The sheet's holidays, earliest first. */
    List<SheetHoliday> findBySheetIdOrderByDayAsc(String sheetId);

    boolean existsBySheetIdAndDay(String sheetId, LocalDate day);
}
