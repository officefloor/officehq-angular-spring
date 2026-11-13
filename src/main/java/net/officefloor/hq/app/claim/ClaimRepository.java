package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** The sheet's claims, newest first. */
    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** A person's claims, newest first. */
    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** A person's claim by its number on the sheet. */
    Optional<Claim> findBySheetIdAndPersonIdAndNumber(String sheetId, Long personId, String number);

    /** The numbers given on the sheet's claims. */
    @Query("select c.number from Claim c where c.sheetId = :sheetId and c.number is not null")
    List<String> numbers(@Param("sheetId") String sheetId);

    Optional<Claim> findByIdAndSheetIdAndPersonId(Long id, String sheetId, Long personId);
}
