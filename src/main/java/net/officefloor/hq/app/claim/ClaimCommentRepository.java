package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {

    /** A sheet's claim comments, oldest first. */
    List<ClaimComment> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's comments, oldest first. */
    List<ClaimComment> findByClaimIdOrderByIdAsc(Long claimId);

    /** Move the lines of the claims to another sheet. */
    @Modifying(flushAutomatically = true)
    @Query("update ClaimComment c set c.sheetId = :sheetId where c.claimId in :claimIds")
    int moveTo(Collection<Long> claimIds, String sheetId);
}
