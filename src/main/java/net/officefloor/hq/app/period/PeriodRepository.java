package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods by from-date. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    /** Whether the sheet has a period of this name, without regard to case. */
    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);
}
