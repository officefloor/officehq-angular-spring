package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {

    /** The sheet's pools in the order they were added. */
    List<Pool> findBySheetIdOrderByIdAsc(String sheetId);

    /** The sheet's pool with this name, ignoring case. */
    Optional<Pool> findBySheetIdAndNameIgnoreCase(String sheetId, String name);
}
