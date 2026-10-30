package net.officefloor.hq.app.sheet;

import java.util.Collection;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    @Query("select s.lastClaimNumber from Sheet s where s.id = :id")
    int lastClaimNumber(@Param("id") String id);

    /** The last claim number given on any of the sheets. */
    @Query("select coalesce(max(s.lastClaimNumber), 0) from Sheet s where s.id in :ids")
    int lastClaimNumberOf(@Param("ids") Collection<String> ids);

    /** Record the sheet's last claim number given; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.lastClaimNumber = :n where s.id = :id")
    int setLastClaimNumber(@Param("id") String id, @Param("n") int n);

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
