package net.officefloor.hq.app.period;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolloverRepository extends JpaRepository<Rollover, Long> {

    /** The sheet's last rollover, if any. */
    Optional<Rollover> findFirstBySheetIdOrderByIdDesc(String sheetId);
}
