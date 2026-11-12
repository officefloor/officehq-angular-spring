package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimShareRepository extends JpaRepository<ClaimShare, Long> {

    /** A sheet's claim shares, oldest first. */
    List<ClaimShare> findBySheetIdOrderByIdAsc(String sheetId);

    /** A claim's shares, oldest first. */
    List<ClaimShare> findByClaimIdOrderByIdAsc(Long claimId);

    /** The shares a person holds, oldest first. */
    List<ClaimShare> findByPersonIdOrderByIdAsc(Long personId);

    /** The shares of the claims, oldest first. */
    List<ClaimShare> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);
}
