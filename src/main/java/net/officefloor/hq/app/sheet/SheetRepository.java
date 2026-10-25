package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Set how many claim numbers the sheet has given out: its last number given. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.claimNumbersUsed = :used where s.id = :id")
    int setClaimNumbersUsed(@Param("id") String id, @Param("used") int used);

    /** The last claim number given on any of the sheets; 0 when none has given one. */
    @Query("select coalesce(max(s.claimNumbersUsed), 0) from Sheet s where s.id in :ids")
    int lastClaimNumber(@Param("ids") Collection<String> ids);

    /** Count one more settle batch; the sheet's row stays locked until the transaction ends. */
    @Modifying(flushAutomatically = true)
    @Query("update Sheet s set s.settleBatchesUsed = s.settleBatchesUsed + 1 where s.id = :id")
    int takeSettleBatch(@Param("id") String id);

    /** How many settle batches the sheet has had. */
    @Query("select s.settleBatchesUsed from Sheet s where s.id = :id")
    int settleBatchesUsed(@Param("id") String id);

    /** How many numbers one of the sheet's per-period sequences has given out; null when none. */
    @Query(value = "select used from claim_number_sequence where sheet_id = :id and sequence_key = :key",
            nativeQuery = true)
    Integer sequenceNumbersUsed(@Param("id") String id, @Param("key") String key);

    /** Start one of the sheet's sequences, with how many numbers it has given out. */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into claim_number_sequence (sheet_id, sequence_key, used) values (:id, :key, :used)",
            nativeQuery = true)
    int startSequence(@Param("id") String id, @Param("key") String key, @Param("used") int used);

    /** Set how many numbers one of the sheet's sequences has given out: its last number given. */
    @Modifying(flushAutomatically = true)
    @Query(value = "update claim_number_sequence set used = :used where sheet_id = :id and sequence_key = :key",
            nativeQuery = true)
    int setSequenceNumbersUsed(@Param("id") String id, @Param("key") String key, @Param("used") int used);

    /** The last number one sequence (by key) has given on any of the sheets; 0 when none has given one. */
    @Query(value = "select coalesce(max(used), 0) from claim_number_sequence where sheet_id in (:ids)"
            + " and sequence_key = :key", nativeQuery = true)
    int lastSequenceNumber(@Param("ids") Collection<String> ids, @Param("key") String key);

    /** Count one more number given out by one of the sheet's per-period sequences. */
    @Modifying(flushAutomatically = true)
    @Query(value = "update claim_number_sequence set used = used + 1 where sheet_id = :id and sequence_key = :key",
            nativeQuery = true)
    int takeSequenceNumber(@Param("id") String id, @Param("key") String key);

    /** The sheet, locked until the transaction ends, so changes that check the sheet as a whole
     *  (such as periods not overlapping) are made one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findForUpdate(@Param("id") String id);

    /** Whether the sheet is archived; false for a sheet never loaded. */
    @Query("select count(s) > 0 from Sheet s where s.id = :id and s.archived = true")
    boolean isArchived(@Param("id") String id);
}
