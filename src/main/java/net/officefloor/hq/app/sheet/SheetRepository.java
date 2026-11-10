package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** A sheet, locked until the end of the transaction so only one user changes it at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sheet s WHERE s.id = :id")
    Optional<Sheet> findLockedById(String id);

    /** The sheet's children: the sheets naming it as their parent. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);

    /** The last claim number given on any of the sheets, as the store holds it now; 0 when none. */
    @Query("SELECT COALESCE(MAX(s.lastClaimNumber), 0) FROM Sheet s WHERE s.id IN :ids")
    int maxLastClaimNumber(java.util.Collection<String> ids);
}
