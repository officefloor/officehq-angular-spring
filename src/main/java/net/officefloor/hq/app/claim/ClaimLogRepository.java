package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimLogRepository extends JpaRepository<ClaimLog, Long> {

    /** The log lines of the claims, oldest first. */
    List<ClaimLog> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);
}
