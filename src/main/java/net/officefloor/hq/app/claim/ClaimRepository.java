package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The last place given in a period sequence on the sheet; 0 before any. */
    @Query("select coalesce(max(c.periodSeqNumber), 0) from Claim c where c.sheetId = :sheetId and c.periodSequence = :sequence")
    int lastInSequence(@Param("sheetId") String sheetId, @Param("sequence") String sequence);
}
