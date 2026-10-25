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

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's claims as the screen lists them, newest first by their place in the list. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** The last place given in the sheet's list of claims; 0 when it has none. */
    @Query("select coalesce(max(c.listed), 0) from Claim c where c.sheetId = :sheetId")
    long lastListed(@Param("sheetId") String sheetId);

    /** The person's claims, locked until the transaction ends, so a statement binds them all at one moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.sheetId = :sheetId and c.personId = :personId order by c.id desc")
    List<Claim> findForStatement(@Param("sheetId") String sheetId, @Param("personId") Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The person's claim of the number, if any. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim, locked until the transaction ends, so two status changes cannot both pass. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> findForUpdate(@Param("id") Long id, @Param("sheetId") String sheetId);

    /** Release every lock on the sheet's claims. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int releaseLocks(@Param("sheetId") String sheetId);
}
