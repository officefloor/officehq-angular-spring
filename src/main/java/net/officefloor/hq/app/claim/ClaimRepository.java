package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A person's claims, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** A person's claim by its number on the sheet. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, Long number);

    Optional<Claim> findByIdAndSheetIdAndPersonId(Long id, String sheetId, Long personId);
}
