package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** How many numbers one of the sheet's per-period sequences has given out; null when none. */
    @Query(value = "select used from claim_number_sequence where sheet_id = :id and sequence_key = :key",
            nativeQuery = true)
    Integer sequenceNumbersUsed(@Param("id") String id, @Param("key") String key);

    /** Start one of the sheet's per-period sequences, its first number given out. */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into claim_number_sequence (sheet_id, sequence_key, used) values (:id, :key, 1)",
            nativeQuery = true)
    int startSequence(@Param("id") String id, @Param("key") String key);

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
