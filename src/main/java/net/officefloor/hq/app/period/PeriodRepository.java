package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);
}
