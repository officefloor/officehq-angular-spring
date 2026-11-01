package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends, so only one user at a time changes it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findForUpdate(String id);

    /** The last sheet-wide claim number given on any of the sheets; 0 if none. */
    @Query(value = "select coalesce(max(last_claim_number), 0) from sheet where id in (:ids)", nativeQuery = true)
    long lastClaimNumberAmong(Collection<String> ids);

    /** The last claim number given in the fiscal year on any of the sheets; 0 if none. */
    @Query(value = "select coalesce(max(last_number), 0) from sheet_fiscal_number where sheet_id in (:ids)"
            + " and fiscal_year = :year", nativeQuery = true)
    long lastFiscalNumberAmong(Collection<String> ids, int year);

    /** Keep the number as the last the sheet gave in the fiscal year. */
    @Modifying
    @Query(value = "merge into sheet_fiscal_number (sheet_id, fiscal_year, last_number) key (sheet_id, fiscal_year)"
            + " values (:id, :year, :number)", nativeQuery = true)
    void keepFiscalNumber(String id, int year, long number);
}
