package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimLogRepository extends JpaRepository<ClaimLog, Long> {

    /** A sheet's claim log lines, oldest first. */
    List<ClaimLog> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's log lines, oldest first. */
    List<ClaimLog> findByClaimIdOrderByIdAsc(Long claimId);

    /** Move the lines of the claims to another sheet. */
    @Modifying(flushAutomatically = true)
    @Query("update ClaimLog l set l.sheetId = :sheetId where l.claimId in :claimIds")
    int moveTo(Collection<Long> claimIds, String sheetId);
}
