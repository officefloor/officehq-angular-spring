package net.officefloor.hq.app.period;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The sheet's latest rollover. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);

    /** Whether the sheet's fiscal year has been rolled over and that rollover not undone. */
    boolean existsBySheetIdAndFiscalYearAndUndoneFalse(String sheetId, int fiscalYear);
}
