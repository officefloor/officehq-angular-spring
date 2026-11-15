package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** The sheet's claim lines, in the order they were added. */
    List<ClaimLine> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's lines, in the order they were added. */
    List<ClaimLine> findByClaimIdOrderByIdAsc(Long claimId);
}
