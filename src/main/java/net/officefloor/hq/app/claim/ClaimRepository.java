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

    /** The highest id of any claim on any sheet, 0 when there is none: every claim added later has a greater one. */
    @Query("select coalesce(max(c.id), 0) from Claim c")
    long maxId();

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

    /** Whether a claim on the sheet, other than the one given (null: none), carries the reference, compared without regard to case. */
    @Query("select count(c) > 0 from Claim c where c.sheetId = :sheetId and lower(c.ref) = lower(:ref)"
            + " and (:id is null or c.id <> :id)")
    boolean refUsed(@Param("sheetId") String sheetId, @Param("ref") String ref, @Param("id") Long id);

    /** The person's claims in the order they were added, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.sheetId = :sheetId and c.personId = :personId order by c.id asc")
    List<Claim> findForMove(@Param("sheetId") String sheetId, @Param("personId") Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The person's claim of the number, if any. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The total amount of the person's corrections of the claim of the number, other than the claim given
     *  (null: none), VOID and DELETED ones left out; 0 when there is none. */
    @Query("select coalesce(sum(c.amount), 0) from Claim c where c.sheetId = :sheetId and c.personId = :personId"
            + " and c.corrects = :number and c.amount < 0 and (:id is null or c.id <> :id)"
            + " and c.status not in (net.officefloor.hq.app.claim.ClaimStatus.VOID, net.officefloor.hq.app.claim.ClaimStatus.DELETED)")
    java.math.BigDecimal correctedTotal(@Param("sheetId") String sheetId, @Param("personId") Long personId,
            @Param("number") String number, @Param("id") Long id);

    /** The claim, locked until the transaction ends, so two status changes cannot both pass. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> findForUpdate(@Param("id") Long id, @Param("sheetId") String sheetId);

    /** Release every lock on the sheet's claims. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int releaseLocks(@Param("sheetId") String sheetId);
}
