package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends, so only one user at a time changes it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findForUpdate(String id);

    /** The last sheet-wide claim number given on any of the sheets; 0 if none. */
    @Query(value = "select coalesce(max(last_claim_number), 0) from sheet where id in (:ids)", nativeQuery = true)
    long lastClaimNumberAmong(Collection<String> ids);
}
