package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
    List<Claim> findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(String sheetId, Long personId);
}
