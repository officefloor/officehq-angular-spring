package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** A sheet's claim lines, oldest first. */
    List<ClaimLine> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's lines, oldest first. */
    List<ClaimLine> findByClaimIdOrderByIdAsc(Long claimId);

    /** Whether the claim has any line. */
    boolean existsByClaimId(Long claimId);

    /** Move the lines of the claims to another sheet. */
    @Modifying(flushAutomatically = true)
    @Query("update ClaimLine l set l.sheetId = :sheetId where l.claimId in :claimIds")
    int moveTo(Collection<Long> claimIds, String sheetId);
}
