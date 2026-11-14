package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The sheet's last rollover. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's rollovers of a fiscal year that still stand. */
    List<Rollover> findBySheetIdAndFiscalYearAndUndoneFalse(String sheetId, int fiscalYear);
}
