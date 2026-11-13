package net.officefloor.hq.app.claim;

import java.time.LocalDate;
import java.util.Collection;
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

    /** The sheet's claims as listed: newest first, a reassigned claim as its new person's newest. */
    List<Claim> findBySheetIdOrderByListOrderDesc(String sheetId);

    /** The highest place in the sheet's list; 0 while it has no claims. */
    @Query("select coalesce(max(c.listOrder), 0) from Claim c where c.sheetId = :sheetId")
    long maxListOrder(@Param("sheetId") String sheetId);

    /** The highest claim id on any sheet: every claim added later has a higher one; 0 while there are none. */
    @Query("select coalesce(max(c.id), 0) from Claim c")
    long maxId();

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** Whether a claim on the sheet other than the one excluded (none: every claim) has the reference,
     *  compared without regard to case. */
    @Query("select count(c) > 0 from Claim c where c.sheetId = :sheetId and lower(c.ref) = lower(:ref)"
            + " and (:excluded is null or c.id <> :excluded)")
    boolean refUsed(@Param("sheetId") String sheetId, @Param("ref") String ref, @Param("excluded") Long excluded);

    /** How many of a person's claims in the statuses are due by a day from one date to another, both inclusive. */
    long countBySheetIdAndPersonIdAndStatusInAndDueByBetween(String sheetId, Long personId,
            Collection<ClaimStatus> statuses, LocalDate from, LocalDate to);

    boolean existsBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number,
            ClaimStatus status);

    /** A person's corrections naming a claim's number, but for one (none: no exclusion). */
    @Query("select c from Claim c where c.sheetId = :sheetId and c.personId = :personId and c.corrects = :number"
            + " and (:excluded is null or c.id <> :excluded)")
    List<Claim> correctionsOf(@Param("sheetId") String sheetId, @Param("personId") Long personId,
            @Param("number") String number, @Param("excluded") Long excluded);

    /** A person's claim by its number. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim, locked until the transaction ends, so two users' changes to it go one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findForUpdateByIdAndSheetId(Long id, String sheetId);

    /** A person's claims, locked until the transaction ends, so a statement binds them all at one moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Claim> findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(String sheetId, Long personId);

    /** Release every lock on the sheet's claims: their editors close when the sheet's today changes. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int unlockAll(@Param("sheetId") String sheetId);
}
