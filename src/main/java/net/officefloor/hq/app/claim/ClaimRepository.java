package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The highest claim id given so far, on any sheet; 0 when there is none. */
    @Query("SELECT COALESCE(MAX(c.id), 0) FROM Claim c")
    long highestId();

    /** The person's claim with the number. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The person's corrections of the claim with the number. */
    List<Claim> findBySheetIdAndPersonIdAndCorrects(String sheetId, Long personId, String corrects);

    /** The sheet's claim with the reference (as compared). */
    Optional<Claim> findBySheetIdAndRefKey(String sheetId, String refKey);

    /** The claim, locked until the transaction ends so two changes to it (an edit, a status change) never overlap. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Claim c WHERE c.id = :id AND c.sheetId = :sheetId")
    Optional<Claim> lockByIdAndSheetId(Long id, String sheetId);

    /** The person's claims, newest first, locked until the transaction ends so a statement never
     * overlaps a status change of one of them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Claim c WHERE c.sheetId = :sheetId AND c.personId = :personId ORDER BY c.listed DESC, c.id DESC")
    List<Claim> lockBySheetIdAndPersonId(String sheetId, Long personId);

    /** Release every claim lock on the sheet. */
    @Modifying
    @Query("UPDATE Claim c SET c.lockedBy = null, c.lockedOn = null WHERE c.sheetId = :sheetId AND c.lockedBy IS NOT NULL")
    int unlockBySheetId(String sheetId);
}
