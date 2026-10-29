package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdOrderByIdAsc(String sheetId);

    /** The sheet's claims in list order: newest listed first. */
    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    /** Gives the claim the next place in the list, so it is listed first under its person. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE claim SET listed = NEXT VALUE FOR claim_listing WHERE id = :id", nativeQuery = true)
    void relist(@Param("id") Long id);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    boolean existsBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number,
            ClaimStatus status);

    Optional<Claim> findBySheetIdAndNumber(String sheetId, String number);

    /**
     * The last place given in the sheet's number sequence, by a claim holding it or one that gave it up when
     * re-dated; null when none has been.
     */
    @Query(value = "SELECT MAX(n) FROM (SELECT number_in_sequence AS n FROM claim WHERE sheet_id = :sheetId"
            + " AND number_sequence = :sequence UNION ALL SELECT number_in_sequence FROM claim_number_retired"
            + " WHERE sheet_id = :sheetId AND number_sequence = :sequence) AS given", nativeQuery = true)
    Long lastInSequence(@Param("sheetId") String sheetId, @Param("sequence") String sequence);

    /** Records a period number a claim gave up, so it is never given again. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO claim_number_retired (sheet_id, number_sequence, number_in_sequence)"
            + " VALUES (:sheetId, :sequence, :place)", nativeQuery = true)
    void retireNumber(@Param("sheetId") String sheetId, @Param("sequence") String sequence,
            @Param("place") Long place);

    /** Whether a claim of the sheet other than the one excluded (null for none) carries the reference, in any case. */
    @Query("SELECT COUNT(c) > 0 FROM Claim c WHERE c.sheetId = :sheetId AND LOWER(c.ref) = LOWER(:ref)"
            + " AND (:excluded IS NULL OR c.id <> :excluded)")
    boolean refInUse(@Param("sheetId") String sheetId, @Param("ref") String ref, @Param("excluded") Long excluded);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The claim, locked until the transaction ends, so two status changes to it never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findWithLockByIdAndSheetId(Long id, String sheetId);

    /** The sheet's claims an editor holds. */
    List<Claim> findBySheetIdAndLockedByNotNull(String sheetId);
}
