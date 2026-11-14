package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked for update until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findLockedById(String id);

    /** The sheets naming this one as their parent. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);
}
