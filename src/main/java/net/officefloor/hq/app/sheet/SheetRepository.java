package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Sheet> findWithLockById(String id);

    /** Moves the sheet's last claim number on by one; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_claim_number = last_claim_number + 1 WHERE id = :id", nativeQuery = true)
    int incrementClaimNumber(@Param("id") String id);

    @Query(value = "SELECT last_claim_number FROM sheet WHERE id = :id", nativeQuery = true)
    long lastClaimNumber(@Param("id") String id);

    /** Moves the sheet's last settle batch number on by one; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_settle_batch = last_settle_batch + 1 WHERE id = :id", nativeQuery = true)
    int incrementSettleBatch(@Param("id") String id);

    @Query(value = "SELECT last_settle_batch FROM sheet WHERE id = :id", nativeQuery = true)
    int lastSettleBatch(@Param("id") String id);
}
