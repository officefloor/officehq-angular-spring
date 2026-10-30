package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Take the sheet's next claim number; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.lastClaimNumber = s.lastClaimNumber + 1 where s.id = :id")
    int bumpClaimNumber(@Param("id") String id);

    @Query("select s.lastClaimNumber from Sheet s where s.id = :id")
    int lastClaimNumber(@Param("id") String id);

    /** Take the sheet's next settle batch number; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.lastSettleBatch = s.lastSettleBatch + 1 where s.id = :id")
    int bumpSettleBatch(@Param("id") String id);

    @Query("select s.lastSettleBatch from Sheet s where s.id = :id")
    Integer lastSettleBatch(@Param("id") String id);

    /** Lock the sheet's row until the transaction ends, so changes checked against the whole sheet take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Sheet lockById(@Param("id") String id);
}
