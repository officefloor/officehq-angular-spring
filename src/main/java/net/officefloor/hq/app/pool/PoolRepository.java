package net.officefloor.hq.app.pool;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, Long> {

    List<Pool> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
