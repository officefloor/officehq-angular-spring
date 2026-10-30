package net.officefloor.hq.app.asof;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPastRepository extends JpaRepository<SheetPast, Long> {

    /** The last one written for a day on or before the date. */
    Optional<SheetPast> findFirstBySheetIdAndEndedOnLessThanEqualOrderByIdDesc(String sheetId, LocalDate date);
}
