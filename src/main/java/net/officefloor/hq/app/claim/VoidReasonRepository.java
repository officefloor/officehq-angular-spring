package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoidReasonRepository extends JpaRepository<VoidReason, Long> {

    List<VoidReason> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndReason(String sheetId, String reason);

    boolean existsBySheetId(String sheetId);
}
