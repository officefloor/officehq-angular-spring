package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends, so changes to it are made one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findLockedById(@Param("id") String id);

    /** The last claim number given on any of these sheets, read from the store; 0 when none was given. */
    @Query("select coalesce(max(s.lastClaimNumber), 0) from Sheet s where s.id in :ids")
    int lastClaimNumberOf(@Param("ids") Collection<String> ids);

    /** The sheet's children: the sheets naming it as their parent. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);

    /** The ids of the sheet's descendants: its children, their children and so on (each once, the sheet itself
     *  never, should the line loop back to it). */
    default List<String> descendantsOf(String id) {
        List<String> descendants = new ArrayList<>();
        Set<String> seen = new HashSet<>(List.of(id));
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(id));
        while (!pending.isEmpty()) {
            for (Sheet child : findByParentIdOrderByIdAsc(pending.poll())) {
                if (seen.add(child.getId())) {
                    descendants.add(child.getId());
                    pending.add(child.getId());
                }
            }
        }
        return descendants;
    }

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
