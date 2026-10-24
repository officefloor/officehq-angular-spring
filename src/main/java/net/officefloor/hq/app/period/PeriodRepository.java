package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    Optional<Period> findBySheetIdAndNameKey(String sheetId, String nameKey);
}
