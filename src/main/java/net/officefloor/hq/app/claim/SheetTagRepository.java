package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetTagRepository extends JpaRepository<SheetTag, Long> {

    /** Every tag used on the sheet, by name. */
    List<SheetTag> findBySheetIdOrderByNameAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
