package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A person's claims on the sheet, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** The claim of a person on the sheet with a number. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, Integer number);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
