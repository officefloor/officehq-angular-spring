package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** The sheet's claims as listed: each person's newest (or most recently reassigned to them) first. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByListedDesc(String sheetId, Long personId);

    /** Give a claim the next place in the list, making it the newest of its person's claims. */
    @Modifying(flushAutomatically = true)
    @Query(value = "update claim set listed = next value for claim_listed_seq where id = :id", nativeQuery = true)
    void relist(@Param("id") Long id);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The last place given in a period sequence on the sheet; 0 before any. */
    @Query("select coalesce(max(c.periodSeqNumber), 0) from Claim c where c.sheetId = :sheetId and c.periodSequence = :sequence")
    int lastInSequence(@Param("sheetId") String sheetId, @Param("sequence") String sequence);
}
