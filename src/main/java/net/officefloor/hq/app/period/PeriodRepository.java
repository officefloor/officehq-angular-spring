package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    Optional<Period> findByIdAndSheetId(Long id, String sheetId);

    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);
}
