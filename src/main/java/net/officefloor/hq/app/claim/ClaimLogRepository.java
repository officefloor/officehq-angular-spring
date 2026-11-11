package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimLogRepository extends JpaRepository<ClaimLog, Long> {

    /** A sheet's claim log lines, oldest first. */
    List<ClaimLog> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's log lines, oldest first. */
    List<ClaimLog> findByClaimIdOrderByIdAsc(Long claimId);
}
