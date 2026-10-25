package net.officefloor.hq.app.sheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Count one more claim number given out; the sheet's row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.claimNumbersUsed = s.claimNumbersUsed + 1 where s.id = :id")
    int takeClaimNumber(@Param("id") String id);

    /** How many claim numbers the sheet has given out. */
    @Query("select s.claimNumbersUsed from Sheet s where s.id = :id")
    int claimNumbersUsed(@Param("id") String id);
}
