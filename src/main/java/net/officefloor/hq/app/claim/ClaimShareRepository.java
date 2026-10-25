package net.officefloor.hq.app.claim;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimShareRepository extends JpaRepository<ClaimShare, Long> {

    /** The sheet's shares, in the order they were given. */
    List<ClaimShare> findBySheetIdOrderByIdAsc(String sheetId);

    /** The shares of the claims, in the order they were given. */
    List<ClaimShare> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);

    /** The claim's shares, in the order they were given. */
    List<ClaimShare> findByClaimIdOrderByIdAsc(Long claimId);

    /** The shares the person holds, in the order they were given. */
    List<ClaimShare> findByPersonIdOrderByIdAsc(Long personId);
}
