package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {

    /** The comments on the claims, oldest first. */
    List<ClaimComment> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);
}
