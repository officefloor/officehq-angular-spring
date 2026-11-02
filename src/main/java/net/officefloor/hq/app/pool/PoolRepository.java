package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {

    /** The pools of a sheet in the order they were added. */
    List<Pool> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Pool> findByIdAndSheetId(Long id, String sheetId);

    /** Whether the name is already a pool of the sheet. */
    boolean existsBySheetIdAndName(String sheetId, String name);
}
