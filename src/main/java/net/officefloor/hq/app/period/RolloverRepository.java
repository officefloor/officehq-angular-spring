package net.officefloor.hq.app.period;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The sheet's last rollover. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);

    /** Whether a rollover of the fiscal year on the sheet still stands. */
    boolean existsBySheetIdAndFiscalYearAndUndoneFalse(String sheetId, int fiscalYear);
}
