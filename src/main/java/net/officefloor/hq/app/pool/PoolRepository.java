package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {

    /** The sheet's pools in the order they were added. */
    List<Pool> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Pool> findBySheetIdAndId(String sheetId, Long id);

    /** The pools the crews are, in the order they were added. */
    List<Pool> findByCrewIdInOrderByIdAsc(java.util.Collection<Long> crewIds);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
