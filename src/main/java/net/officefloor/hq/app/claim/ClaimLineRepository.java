package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** The lines of the claims, in the order they were added. */
    List<ClaimLine> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);

    /** Move the lines of the claim {@code from} to the claim {@code to}. */
    @Modifying
    @Query("UPDATE ClaimLine l SET l.claimId = :to WHERE l.claimId = :from")
    int moveClaim(Long from, Long to);
}
