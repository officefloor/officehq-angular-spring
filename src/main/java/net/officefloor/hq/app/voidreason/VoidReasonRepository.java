package net.officefloor.hq.app.voidreason;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoidReasonRepository extends JpaRepository<VoidReason, Long> {

    /** The sheet's void reasons, in the order they were added. */
    List<VoidReason> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the reason is on the sheet's list. */
    boolean existsBySheetIdAndReason(String sheetId, String reason);
}
