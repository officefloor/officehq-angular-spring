package net.officefloor.hq.app.claim;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The claims on a sheet, newest first (the one listed last first). */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** A person's claims on a sheet, newest first (the one listed last first). */
    List<Claim> findBySheetIdAndPersonIdOrderByListedDesc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** A person's claim on a sheet of the number and status. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number,
            ClaimStatus status);

    /** Whether a claim on a sheet other than the one given (null: none) has the reference, comparing
     *  without regard to case. */
    @Query("select count(c) > 0 from Claim c where c.sheetId = :sheetId and upper(c.ref) = upper(:ref)"
            + " and (:exceptId is null or c.id <> :exceptId)")
    boolean refUsed(String sheetId, String ref, Long exceptId);

    /** How many of a person's claims on a sheet are open or pending and due by a day from one date
     *  to another, inclusive. */
    @Query("select count(c) from Claim c where c.sheetId = :sheetId and c.personId = :personId"
            + " and c.status in (net.officefloor.hq.app.claim.ClaimStatus.OPEN, net.officefloor.hq.app.claim.ClaimStatus.PENDING)"
            + " and c.dueBy >= :from and c.dueBy <= :to")
    long countOpenDueBetween(String sheetId, Long personId, LocalDate from, LocalDate to);

    /** The next place a claim is listed at, after every claim listed so far. */
    @Query(value = "select next value for claim_listed_seq", nativeQuery = true)
    long nextListed();

    /** A claim on a sheet, locked for the rest of the transaction so two status changes to it
     *  happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> lockByIdAndSheetId(Long id, String sheetId);

    /** Release every claim editor held open on a sheet (its today changed). */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.editLockedBy = null where c.sheetId = :sheetId and c.editLockedBy is not null")
    int releaseEditLocks(String sheetId);

    /** Release the editors a user holds open on a person's other claims. */
    @Modifying(flushAutomatically = true)
    @Query("update Claim c set c.editLockedBy = null where c.sheetId = :sheetId and c.personId = :personId"
            + " and c.editLockedBy = :user and c.id <> :keepId")
    int releaseOtherEditLocks(String sheetId, Long personId, String user, Long keepId);
}
