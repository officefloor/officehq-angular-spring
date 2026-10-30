package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    Optional<Period> findByIdAndSheetId(long id, String sheetId);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);
}
