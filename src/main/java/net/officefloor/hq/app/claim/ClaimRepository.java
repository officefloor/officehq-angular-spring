package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findBySheetIdOrderByIdDesc(String sheetId);

    /** The sheet's claims in list order: newest first, a moved claim as the newest when it moved. */
    List<Claim> findBySheetIdOrderByListedDesc(String sheetId);

    /** The next place in the claims' list, after every claim added or moved so far. */
    @Query(value = "select next value for claim_listed_seq", nativeQuery = true)
    long nextListed();

    List<Claim> findBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);

    /**
     * The last place given in a period's numbering on the sheet (empty name: in no period), counting those
     * given up by claims re-dated out of it; 0 if none.
     */
    @Query(value = "select coalesce(max(s), 0) from (select number_seq s from claim where sheet_id = :sheetId"
            + " and number_period = :period union all select number_seq s from claim_number_retired"
            + " where sheet_id = :sheetId and number_period = :period) n", nativeQuery = true)
    int lastNumberWithin(String sheetId, String period);

    /** Give up a place in a period's numbering on the sheet, so it is never given again. */
    @Modifying
    @Query(value = "insert into claim_number_retired (sheet_id, number_period, number_seq)"
            + " values (:sheetId, :period, :seq)", nativeQuery = true)
    void retireNumberWithin(String sheetId, String period, int seq);

    /** Release every claim editor's lock on the sheet. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update claim set locked_by = null where sheet_id = :sheetId and locked_by is not null",
            nativeQuery = true)
    void unlockAll(String sheetId);

    /** The claim on the sheet carrying the reference of this key, if any. */
    Optional<Claim> findBySheetIdAndRefKey(String sheetId, String refKey);

    Optional<Claim> findByIdAndSheetId(Long id, String sheetId);
}
