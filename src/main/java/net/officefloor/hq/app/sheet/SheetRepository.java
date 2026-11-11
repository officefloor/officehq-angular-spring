package net.officefloor.hq.app.sheet;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet's children: the sheets naming it as their parent. */
    List<Sheet> findByParentOrderByIdAsc(String parent);

    /** Moves the sheet's last list place on by one; the row stays locked until the transaction ends. */
    @Modifying
    @Query(value = "UPDATE sheet SET last_listed = last_listed + 1 WHERE id = :id", nativeQuery = true)
    int incrementLastListed(@Param("id") String id);

    /** Sets the sheet's last list place, for a sheet whose claims were given their places as they were elsewhere. */
    @Modifying
    @Query(value = "UPDATE sheet SET last_listed = :listed WHERE id = :id", nativeQuery = true)
    int setLastListed(@Param("id") String id, @Param("listed") long listed);

    @Query(value = "SELECT last_listed FROM sheet WHERE id = :id", nativeQuery = true)
    long lastListed(@Param("id") String id);

    /** Moves the sheet's last settle batch on by one; the row stays locked until the transaction ends. */
    @Modifying
    @Query(value = "UPDATE sheet SET last_settle_batch = last_settle_batch + 1 WHERE id = :id", nativeQuery = true)
    int incrementLastSettleBatch(@Param("id") String id);

    @Query(value = "SELECT last_settle_batch FROM sheet WHERE id = :id", nativeQuery = true)
    long lastSettleBatch(@Param("id") String id);

    /** Locks the sheet's row until the transaction ends; 0 when the sheet does not exist. */
    @Modifying
    @Query(value = "UPDATE sheet SET id = id WHERE id = :id", nativeQuery = true)
    int lockById(@Param("id") String id);

    @Query(value = "SELECT last_claim_number FROM sheet WHERE id = :id", nativeQuery = true)
    long lastClaimNumber(@Param("id") String id);

    /** The last claim number given on any of the sheets; 0 when none has been given. */
    @Query(value = "SELECT COALESCE(MAX(last_claim_number), 0) FROM sheet WHERE id IN (:ids)", nativeQuery = true)
    long maxLastClaimNumber(@Param("ids") Collection<String> ids);

    /** Sets the sheet's last claim number; the row stays locked until the transaction ends. */
    @Modifying
    @Query(value = "UPDATE sheet SET last_claim_number = :number WHERE id = :id", nativeQuery = true)
    int setLastClaimNumber(@Param("id") String id, @Param("number") long number);

    @Query(value = "SELECT parent FROM sheet WHERE id = :id", nativeQuery = true)
    String parentOf(@Param("id") String id);

    @Query(value = "SELECT id FROM sheet WHERE parent = :id ORDER BY id", nativeQuery = true)
    List<String> childIdsOf(@Param("id") String id);

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
