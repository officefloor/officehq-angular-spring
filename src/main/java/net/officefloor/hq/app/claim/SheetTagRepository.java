package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetTagRepository extends JpaRepository<SheetTag, Long> {

    List<SheetTag> findBySheetIdOrderByTagAsc(String sheetId);

    boolean existsBySheetIdAndTag(String sheetId, String tag);
}
