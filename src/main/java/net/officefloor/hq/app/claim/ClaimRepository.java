package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByListedDescIdDesc(String sheetId, Long personId);

    /** The last listing given on the sheet, 0 with no claims. */
    @Query("select coalesce(max(c.listed), 0) from Claim c where c.sheetId = :sheetId")
    long lastListed(@Param("sheetId") String sheetId);

    /** Whether a claim on the sheet has this external reference, without regard to case. */
    boolean existsBySheetIdAndRefIgnoreCase(String sheetId, String ref);

    /** Whether a claim on the sheet other than this one has this external reference, without regard to case. */
    boolean existsBySheetIdAndRefIgnoreCaseAndIdNot(String sheetId, String ref, Long id);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);

    /** Release every claim editor's lock on the sheet. */
    @Modifying
    @Query("update Claim c set c.lockedBy = null where c.sheetId = :sheetId and c.lockedBy is not null")
    int releaseLocks(@Param("sheetId") String sheetId);
}
