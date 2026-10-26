package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    boolean existsBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number,
            ClaimStatus status);

    Optional<Claim> findBySheetIdAndNumber(String sheetId, String number);

    /** The last place given in the sheet's number sequence; null when none has been. */
    @Query("SELECT MAX(c.numberInSequence) FROM Claim c WHERE c.sheetId = :sheetId AND c.numberSequence = :sequence")
    Long lastInSequence(@Param("sheetId") String sheetId, @Param("sequence") String sequence);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The claim, locked until the transaction ends, so two status changes to it never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findWithLockByIdAndSheetId(Long id, String sheetId);
}
