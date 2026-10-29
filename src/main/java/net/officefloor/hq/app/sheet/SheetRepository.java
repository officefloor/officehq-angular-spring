package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet's children: the sheets naming it as their parent. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);

    /** The sheet, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Sheet> findWithLockById(String id);

    /** The last claim number given in the fiscal year on any of the sheets; 0 for none. */
    @Query(value = "SELECT COALESCE(MAX(last_claim_number), 0) FROM sheet_fiscal_claim_number"
            + " WHERE sheet_id IN (:ids) AND fiscal_year = :year", nativeQuery = true)
    long lastFiscalClaimNumberOf(@Param("ids") Collection<String> ids, @Param("year") int year);

    /** Sets the sheet's last claim number in the fiscal year. */
    @Modifying(flushAutomatically = true)
    @Query(value = "MERGE INTO sheet_fiscal_claim_number (sheet_id, fiscal_year, last_claim_number)"
            + " KEY (sheet_id, fiscal_year) VALUES (:id, :year, :number)", nativeQuery = true)
    int setFiscalClaimNumber(@Param("id") String id, @Param("year") int year, @Param("number") long number);

    /** Moves the sheet's last settle batch number on by one; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_settle_batch = last_settle_batch + 1 WHERE id = :id", nativeQuery = true)
    int incrementSettleBatch(@Param("id") String id);

    @Query(value = "SELECT last_settle_batch FROM sheet WHERE id = :id", nativeQuery = true)
    int lastSettleBatch(@Param("id") String id);
}
