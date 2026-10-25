package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /** The sheet's holidays in date order. */
    List<Holiday> findBySheetIdOrderByDateAsc(String sheetId);

    boolean existsBySheetIdAndDate(String sheetId, LocalDate date);
}
