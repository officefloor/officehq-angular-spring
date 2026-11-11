package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** A sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A sheet's claims as listed: the latest list place (the newest added, or the last reassigned) first. */
    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The person's claim with the number (numbers are unique on a sheet). */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim, locked until the transaction ends, so two changes of its status do not interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> findForUpdate(Long id, String sheetId);

    /** Release every claim editor's lock on the sheet. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int unlockAll(String sheetId);
}
