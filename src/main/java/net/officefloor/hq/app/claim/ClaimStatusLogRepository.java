package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimStatusLogRepository extends JpaRepository<ClaimStatusLog, Long> {

    List<ClaimStatusLog> findBySheetIdOrderByIdAsc(String sheetId);

    List<ClaimStatusLog> findByClaimIdOrderByIdAsc(Long claimId);
}
