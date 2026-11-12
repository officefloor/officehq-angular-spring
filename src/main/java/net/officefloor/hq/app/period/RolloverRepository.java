package net.officefloor.hq.app.period;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The sheet's last rollover. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);

    /** Whether the fiscal year has been rolled over on the sheet and not undone since. */
    boolean existsBySheetIdAndFyAndUndoneFalse(String sheetId, String fy);
}
