package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** The next place a claim is listed at, after every claim listed so far. */
    @Query(value = "select next value for claim_listed_seq", nativeQuery = true)
    long nextListed();

    /** A claim on a sheet, locked for the rest of the transaction so two status changes to it
     *  happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> lockByIdAndSheetId(Long id, String sheetId);
}
