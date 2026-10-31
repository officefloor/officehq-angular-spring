package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByListedDescIdDesc(String sheetId);

    List<Claim> findBySheetIdAndPersonIdOrderByListedDescIdDesc(String sheetId, Long personId);

    /** The last listing given on the sheet, 0 with no claims. */
    @Query("select coalesce(max(c.listed), 0) from Claim c where c.sheetId = :sheetId")
    long lastListed(@Param("sheetId") String sheetId);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
