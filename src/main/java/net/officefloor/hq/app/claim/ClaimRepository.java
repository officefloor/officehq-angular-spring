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

    /** The sheet's claims an editor holds locked. */
    List<Claim> findBySheetIdAndLockedByIsNotNull(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /**
     * The last place given in a period sequence on the sheet, counting the places claims left when re-dated
     * into another period; 0 before any.
     */
    @Query(value = "select greatest("
            + "(select coalesce(max(period_seq_number), 0) from claim where sheet_id = :sheetId and period_sequence = :sequence), "
            + "(select coalesce(max(period_seq_number), 0) from claim_retired_number where sheet_id = :sheetId and period_sequence = :sequence))",
            nativeQuery = true)
    int lastInSequence(@Param("sheetId") String sheetId, @Param("sequence") String sequence);

    /** Keep a place a claim leaves in a period sequence, so it is never given again. */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into claim_retired_number (sheet_id, period_sequence, period_seq_number) "
            + "values (:sheetId, :sequence, :n)", nativeQuery = true)
    void retireNumber(@Param("sheetId") String sheetId, @Param("sequence") String sequence, @Param("n") int n);
}
