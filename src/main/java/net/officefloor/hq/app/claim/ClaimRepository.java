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

    /** The person's claims, locked until the transaction ends, so a statement binds them all at one moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.sheetId = :sheetId and c.personId = :personId order by c.id desc")
    List<Claim> findForStatement(@Param("sheetId") String sheetId, @Param("personId") Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** The person's claim of the number, if any. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The claim, locked until the transaction ends, so two status changes cannot both pass. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> findForUpdate(@Param("id") Long id, @Param("sheetId") String sheetId);
}
