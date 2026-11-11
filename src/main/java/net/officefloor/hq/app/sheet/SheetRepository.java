package net.officefloor.hq.app.sheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Moves the sheet's last claim number on by one; the row stays locked until the transaction ends. */
    @Modifying
    @Query(value = "UPDATE sheet SET last_claim_number = last_claim_number + 1 WHERE id = :id", nativeQuery = true)
    int incrementLastClaimNumber(@Param("id") String id);

    /** Locks the sheet's row until the transaction ends; 0 when the sheet does not exist. */
    @Modifying
    @Query(value = "UPDATE sheet SET id = id WHERE id = :id", nativeQuery = true)
    int lockById(@Param("id") String id);

    @Query(value = "SELECT last_claim_number FROM sheet WHERE id = :id", nativeQuery = true)
    long lastClaimNumber(@Param("id") String id);

    /** Moves a sequence's last number on by one, starting it at 1; the sheet's row must be locked by the caller. */
    @Modifying
    @Query(value = "MERGE INTO claim_number_sequence s USING (VALUES (:id, :key)) v (sheet_id, sequence_key)"
            + " ON s.sheet_id = v.sheet_id AND s.sequence_key = v.sequence_key"
            + " WHEN MATCHED THEN UPDATE SET last_number = s.last_number + 1"
            + " WHEN NOT MATCHED THEN INSERT (sheet_id, sequence_key, last_number) VALUES (v.sheet_id, v.sequence_key, 1)",
            nativeQuery = true)
    int incrementSequence(@Param("id") String id, @Param("key") String key);

    @Query(value = "SELECT last_number FROM claim_number_sequence WHERE sheet_id = :id AND sequence_key = :key",
            nativeQuery = true)
    long lastSequenceNumber(@Param("id") String id, @Param("key") String key);
}
