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

    boolean existsBySheetIdAndPersonIdAndNumberAndStatus(String sheetId, Long personId, String number,
            ClaimStatus status);

    /** A person's claim by its number. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim, locked until the transaction ends, so two users' changes to it go one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findForUpdateByIdAndSheetId(Long id, String sheetId);

    /** A person's claims, locked until the transaction ends, so a statement binds them all at one moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Claim> findForUpdateBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);
}
