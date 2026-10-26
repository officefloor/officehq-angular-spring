package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {

    List<Pool> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Pool> findBySheetIdAndName(String sheetId, String name);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
