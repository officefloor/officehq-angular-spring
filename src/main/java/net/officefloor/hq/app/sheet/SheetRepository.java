package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends, so changes to it are made one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findLockedById(@Param("id") String id);

    /** The ids of the sheet's ancestors, nearest first: its parent, its parent's parent and so on, at most
     *  {@code levels} of them; the line stops at a sheet with no parent (or one that names a sheet already in it). */
    default List<String> ancestorsOf(String id, int levels) {
        List<String> ancestors = new ArrayList<>();
        String at = id;
        while (ancestors.size() < levels) {
            String parent = findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || parent.equals(id) || ancestors.contains(parent)) {
                break;
            }
            ancestors.add(parent);
            at = parent;
        }
        return ancestors;
    }
}
