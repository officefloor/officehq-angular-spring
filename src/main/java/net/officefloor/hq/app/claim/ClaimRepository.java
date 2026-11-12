package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The claim, locked until the transaction ends, so two users' changes to it go one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findForUpdateByIdAndSheetId(Long id, String sheetId);
}
