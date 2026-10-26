package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The claim, locked until the transaction ends, so two status changes to it never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findWithLockByIdAndSheetId(Long id, String sheetId);
}
