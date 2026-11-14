package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods, by from-date. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    Optional<Period> findByIdAndSheetId(long id, String sheetId);
}
