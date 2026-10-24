package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPoolRepository extends JpaRepository<SheetPool, Long> {

    List<SheetPool> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);

    Optional<SheetPool> findByIdAndSheetId(Long id, String sheetId);
}
