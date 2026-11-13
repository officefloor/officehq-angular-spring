package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest listed first. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** A person's claims, newest listed first. */
    List<Claim> findBySheetIdAndPersonIdOrderByListedDesc(String sheetId, Long personId);

    /** A person's claim by its number on the sheet. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The numbers given on the sheet's claims. */
    @Query("select c.number from Claim c where c.sheetId = :sheetId and c.number is not null")
    List<String> numbers(@Param("sheetId") String sheetId);

    /** The numbers given up on the sheet by re-dated claims: never given again. */
    @Query(value = "SELECT claim_number FROM retired_claim_number WHERE sheet_id = :sheetId", nativeQuery = true)
    List<String> retiredNumbers(@Param("sheetId") String sheetId);

    /** Give up a claim number on the sheet for good. */
    @Modifying(flushAutomatically = true)
    @Query(value = "MERGE INTO retired_claim_number (sheet_id, claim_number) KEY (sheet_id, claim_number) VALUES (:sheetId, :number)",
            nativeQuery = true)
    void retireNumber(@Param("sheetId") String sheetId, @Param("number") String number);

    /** List a claim anew: it becomes the newest under its person. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE claim SET listed = NEXT VALUE FOR claim_listed_seq WHERE id = :id", nativeQuery = true)
    void relist(@Param("id") Long id);

    /** Release every editor's lock on the sheet's claims. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int releaseLocks(@Param("sheetId") String sheetId);

    Optional<Claim> findByIdAndSheetIdAndPersonId(Long id, String sheetId, Long personId);
}
