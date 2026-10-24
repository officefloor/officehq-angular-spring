package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimLogRepository extends JpaRepository<ClaimLog, Long> {

    /** The log lines of the claims, oldest first. */
    List<ClaimLog> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);

    /** Move the log lines of the claim {@code from} to the claim {@code to}. */
    @Modifying
    @Query("UPDATE ClaimLog l SET l.claimId = :to WHERE l.claimId = :from")
    int moveClaim(Long from, Long to);
}
