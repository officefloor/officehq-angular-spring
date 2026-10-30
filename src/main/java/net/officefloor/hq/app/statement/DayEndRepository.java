package net.officefloor.hq.app.statement;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DayEndRepository extends JpaRepository<DayEnd, Long> {

    /** The last one written for a day on or before the date. */
    Optional<DayEnd> findFirstBySheetIdAndEndedOnLessThanEqualOrderByIdDesc(String sheetId, LocalDate date);
}
