package net.officefloor.hq.app.voidreason;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetVoidReasonRepository extends JpaRepository<SheetVoidReason, Long> {

    List<SheetVoidReason> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetId(String sheetId);

    boolean existsBySheetIdAndReason(String sheetId, String reason);
}
