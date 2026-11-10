package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** A sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A sheet's claims as listed: newest (last added or moved) first. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** Give a claim the next place in the list, so it is listed first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE claim SET listed = NEXT VALUE FOR claim_listed_seq WHERE id = :id", nativeQuery = true)
    void listFirst(@Param("id") Long id);

    /** A person's claims, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** Release every claim editor's lock on a sheet. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Claim c SET c.lockedBy = NULL WHERE c.sheetId = :sheetId AND c.lockedBy IS NOT NULL")
    void unlockAll(@Param("sheetId") String sheetId);

    /** Whether a claim on the sheet has the reference, without regard to case. */
    boolean existsBySheetIdAndRefIgnoreCase(String sheetId, String ref);

    /** Whether a claim on the sheet other than the one of {@code id} has the reference, without regard to case. */
    boolean existsBySheetIdAndRefIgnoreCaseAndIdNot(String sheetId, String ref, Long id);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
