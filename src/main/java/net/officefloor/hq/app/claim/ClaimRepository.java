package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's claims as they are listed, newest first. */
    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    /** Give the claim the next place it is listed at: it is listed as the newest. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update claim set listed = next value for claim_listed_seq where id = :id", nativeQuery = true)
    int relist(@Param("id") Long id);

    /** A person's claims on the sheet, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** The claim of a person on the sheet with a number. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim on the sheet with a reference key. */
    Optional<Claim> findBySheetIdAndRefKey(String sheetId, String refKey);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** Release every editor's lock on the sheet's claims. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int unlockAll(@Param("sheetId") String sheetId);
}
