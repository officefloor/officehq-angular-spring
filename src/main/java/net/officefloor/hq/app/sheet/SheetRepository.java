package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheets naming {@code parentId} as their parent: its children. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);

    /** Advance the sheet's last claim number; the row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_claim_number = last_claim_number + 1 WHERE id = :id", nativeQuery = true)
    int advanceClaimNumber(String id);

    @Query(value = "SELECT last_claim_number FROM sheet WHERE id = :id", nativeQuery = true)
    int lastClaimNumber(String id);

    /** Lock the sheet's row until the transaction ends, so its per-period sequences advance one at a time. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sheet SET last_claim_number = last_claim_number WHERE id = :id", nativeQuery = true)
    int lockSheet(String id);

    /** Advance the sheet's sequence named {@code key}; 0 when it has not been started. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE claim_sequence SET last_number = last_number + 1 WHERE sheet_id = :id AND seq_key = :key",
            nativeQuery = true)
    int advanceSequence(String id, String key);

    /** Start the sheet's sequence named {@code key} at 1. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO claim_sequence (sheet_id, seq_key, last_number) VALUES (:id, :key, 1)",
            nativeQuery = true)
    int startSequence(String id, String key);

    @Query(value = "SELECT last_number FROM claim_sequence WHERE sheet_id = :id AND seq_key = :key", nativeQuery = true)
    int lastInSequence(String id, String key);
}
