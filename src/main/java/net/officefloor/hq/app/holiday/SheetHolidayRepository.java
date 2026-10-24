package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetHolidayRepository extends JpaRepository<SheetHoliday, Long> {

    List<SheetHoliday> findBySheetIdOrderByDateAsc(String sheetId);

    boolean existsBySheetIdAndDate(String sheetId, LocalDate date);
}
