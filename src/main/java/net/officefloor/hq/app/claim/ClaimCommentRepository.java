package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {

    /** A sheet's claim comments, oldest first. */
    List<ClaimComment> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's comments, oldest first. */
    List<ClaimComment> findByClaimIdOrderByIdAsc(Long claimId);
}
