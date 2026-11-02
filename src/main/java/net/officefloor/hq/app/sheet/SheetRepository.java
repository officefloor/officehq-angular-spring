package net.officefloor.hq.app.sheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Move the sheet's last claim number on by one; the row stays locked until the transaction
     *  ends, so two claims opened at once get one number each. */
    @Modifying
    @Query("update Sheet s set s.lastClaimNumber = s.lastClaimNumber + 1 where s.id = :id")
    int advanceClaimNumber(String id);

    @Query("select s.lastClaimNumber from Sheet s where s.id = :id")
    int lastClaimNumber(String id);

    /** Move the sheet's last X-number (for claims in no period) on by one; the row stays locked
     *  until the transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastUnperiodNumber = s.lastUnperiodNumber + 1 where s.id = :id")
    int advanceUnperiodNumber(String id);

    @Query("select s.lastUnperiodNumber from Sheet s where s.id = :id")
    int lastUnperiodNumber(String id);

    /** Move the sheet's last settle batch number on by one; the row stays locked until the
     *  transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastSettleBatch = s.lastSettleBatch + 1 where s.id = :id")
    int advanceSettleBatch(String id);

    @Query("select s.lastSettleBatch from Sheet s where s.id = :id")
    Integer lastSettleBatch(String id);
}
