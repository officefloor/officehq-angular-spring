package net.officefloor.hq.app.holiday;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findBySheetIdOrderByDayAsc(String sheetId);

    boolean existsBySheetIdAndDay(String sheetId, java.time.LocalDate day);
}
