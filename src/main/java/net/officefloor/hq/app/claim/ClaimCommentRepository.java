package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {

    /** The comments on the claims, oldest first. */
    List<ClaimComment> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);

    /** Move the comments on the claim {@code from} to the claim {@code to}. */
    @Modifying
    @Query("UPDATE ClaimComment c SET c.claimId = :to WHERE c.claimId = :from")
    int moveClaim(Long from, Long to);
}
