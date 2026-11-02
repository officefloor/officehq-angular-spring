package net.officefloor.hq.app.voidreason;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoidReasonRepository extends JpaRepository<VoidReason, Long> {

    /** The void reasons of a sheet in the order they were added. */
    List<VoidReason> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the reason is already on the sheet's list. */
    boolean existsBySheetIdAndReason(String sheetId, String reason);

    /** Whether the sheet has any void reason. */
    boolean existsBySheetId(String sheetId);
}
