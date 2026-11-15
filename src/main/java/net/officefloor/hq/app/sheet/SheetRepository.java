package net.officefloor.hq.app.sheet;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked so two users cannot take the same claim number at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findForUpdate(String id);

    /** The sheets naming the sheet as their parent: its children, by id. */
    List<Sheet> findByParentOrderByIdAsc(String parent);
}
