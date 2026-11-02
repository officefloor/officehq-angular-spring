package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The claims on a sheet, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A person's claims on a sheet, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** A claim on a sheet, locked for the rest of the transaction so two status changes to it
     *  happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id and c.sheetId = :sheetId")
    Optional<Claim> lockByIdAndSheetId(Long id, String sheetId);
}
