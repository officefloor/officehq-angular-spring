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

    /** The last claim number given on any of the sheets; 0 for none. */
    @Query(value = "SELECT COALESCE(MAX(last_claim_number), 0) FROM sheet WHERE id IN (:ids)", nativeQuery = true)
    long lastClaimNumberOf(@Param("ids") Collection<String> ids);

    /** Sets the sheet's last claim number. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_claim_number = :number WHERE id = :id", nativeQuery = true)
    int setClaimNumber(@Param("id") String id, @Param("number") long number);

    /** Moves the sheet's last settle batch number on by one; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_settle_batch = last_settle_batch + 1 WHERE id = :id", nativeQuery = true)
    int incrementSettleBatch(@Param("id") String id);

    @Query(value = "SELECT last_settle_batch FROM sheet WHERE id = :id", nativeQuery = true)
    int lastSettleBatch(@Param("id") String id);
}
