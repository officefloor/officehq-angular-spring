package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /** The sheet's holidays, the earliest first. */
    List<Holiday> findBySheetIdOrderByDateAsc(String sheetId);

    /** Whether the date is a holiday on the sheet. */
    boolean existsBySheetIdAndDate(String sheetId, LocalDate date);
}
