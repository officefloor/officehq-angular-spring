package net.officefloor.hq.app.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetVoidReasonRepository extends JpaRepository<SheetVoidReason, Long> {

    /** The sheet's void reasons, in the order they were added. */
    List<SheetVoidReason> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndReason(String sheetId, String reason);
}
