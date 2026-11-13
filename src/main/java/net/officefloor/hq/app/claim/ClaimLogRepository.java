package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimLogRepository extends JpaRepository<ClaimLogEntry, Long> {

    /** The sheet's status log lines, oldest first. */
    List<ClaimLogEntry> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's status log lines, oldest first. */
    List<ClaimLogEntry> findByClaimIdOrderByIdAsc(Long claimId);
}
