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
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's claims as they are listed: newest (or most recently reassigned) first. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** The highest place a claim on the sheet is listed at; 0 with none. */
    @Query("select coalesce(max(c.listed), 0) from Claim c where c.sheetId = :sheetId")
    long findLastListed(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The person's claim of a number (the one a correction corrects). */
    Optional<Claim> findFirstBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    boolean existsBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number, ClaimStatus status);

    /** The numbers given to claims on the sheet. */
    @Query("select c.number from Claim c where c.sheetId = :sheetId and c.number is not null")
    List<String> findNumbers(String sheetId);

    /** Whether another claim on the sheet than the one of the id (null: any) carries the reference,
     *  comparing without regard to case. */
    @Query("select count(c) > 0 from Claim c where c.sheetId = :sheetId and lower(c.ref) = lower(:ref)"
            + " and (:id is null or c.id <> :id)")
    boolean existsRef(String sheetId, String ref, Long id);

    /** The claim, locked for a status change or edit so two users cannot change it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> findForUpdate(Long id, String sheetId);

    /** A person's claims, newest first, locked while a statement binds them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.personId = :personId and c.sheetId = :sheetId order by c.listed desc")
    List<Claim> findByPersonForUpdate(Long personId, String sheetId);

    /** The sheet's claims, locked while a holiday re-fixes their due-by. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.sheetId = :sheetId order by c.id")
    List<Claim> findBySheetIdForUpdate(String sheetId);

    /** Release every claim's lock on the sheet (its today changed). */
    @Modifying
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int unlockAll(String sheetId);
}
