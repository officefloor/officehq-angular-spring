package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** The lines of the sheet's claims, oldest first. */
    @Query("SELECT l FROM ClaimLine l WHERE l.claimId IN (SELECT c.id FROM Claim c WHERE c.sheetId = :sheetId)"
            + " ORDER BY l.id")
    List<ClaimLine> ofSheet(@Param("sheetId") String sheetId);

    /** A claim's lines, oldest first. */
    List<ClaimLine> findByClaimIdOrderByIdAsc(Long claimId);
}
