package net.officefloor.hq.app.period;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The last rollover made on a sheet, if any. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);

    /** The rollovers of a fiscal year on a sheet that have not been undone. */
    List<Rollover> findBySheetIdAndFyAndUndoneFalse(String sheetId, String fy);
}
