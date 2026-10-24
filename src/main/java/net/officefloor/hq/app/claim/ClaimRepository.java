package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The claim, locked until the transaction ends so two status changes to it never overlap. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Claim c WHERE c.id = :id AND c.sheetId = :sheetId")
    Optional<Claim> lockByIdAndSheetId(Long id, String sheetId);

    /** The person's claims, newest first, locked until the transaction ends so a statement never
     * overlaps a status change of one of them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Claim c WHERE c.sheetId = :sheetId AND c.personId = :personId ORDER BY c.id DESC")
    List<Claim> lockBySheetIdAndPersonId(String sheetId, Long personId);
}
