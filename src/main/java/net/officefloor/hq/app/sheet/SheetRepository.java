package net.officefloor.hq.app.sheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Moves the sheet's last claim number on by one; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_claim_number = last_claim_number + 1 WHERE id = :id", nativeQuery = true)
    int incrementClaimNumber(@Param("id") String id);

    @Query(value = "SELECT last_claim_number FROM sheet WHERE id = :id", nativeQuery = true)
    long lastClaimNumber(@Param("id") String id);
}
