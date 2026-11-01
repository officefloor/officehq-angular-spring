package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's claims in list order: newest first, a moved claim as the newest when it moved. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** The next place in the claims' list, after every claim added or moved so far. */
    @Query(value = "select next value for claim_listed_seq", nativeQuery = true)
    long nextListed();

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /** The last place given in a period's numbering on the sheet (empty name: in no period); 0 if none. */
    @Query("select coalesce(max(c.numberSeq), 0) from Claim c where c.sheetId = :sheetId and c.numberPeriod = :period")
    int lastNumberWithin(String sheetId, String period);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
