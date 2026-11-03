package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Lock the sheet's row until the transaction ends (a no-op update); 0 when there is no such sheet. */
    @Modifying
    @Query("update Sheet s set s.lastClaimNumber = s.lastClaimNumber where s.id = :id")
    int lockClaimNumber(String id);

    /** Move the sheet's last X-number (for claims in no period) on by one; the row stays locked
     *  until the transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastUnperiodNumber = s.lastUnperiodNumber + 1 where s.id = :id")
    int advanceUnperiodNumber(String id);

    @Query("select s.lastUnperiodNumber from Sheet s where s.id = :id")
    int lastUnperiodNumber(String id);

    /** Move the sheet's last settle batch number on by one; the row stays locked until the
     *  transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastSettleBatch = s.lastSettleBatch + 1 where s.id = :id")
    int advanceSettleBatch(String id);

    @Query("select s.lastSettleBatch from Sheet s where s.id = :id")
    Integer lastSettleBatch(String id);

    /** The sheets naming a sheet as their parent: its children. */
    List<Sheet> findByParentId(String parentId);

    /** The ids of a sheet's descendants: its children, their children, and so on, each once (a
     *  cycle of parents stops where it comes round again). */
    default List<String> descendants(String id) {
        List<String> down = new ArrayList<>();
        List<String> at = List.of(id);
        while (!at.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String parent : at) {
                for (Sheet child : findByParentId(parent)) {
                    if (!child.getId().equals(id) && !down.contains(child.getId())) {
                        down.add(child.getId());
                        next.add(child.getId());
                    }
                }
            }
            at = next;
        }
        return down;
    }

    /** The id of the root of the sheet's tree: its topmost ancestor, or the sheet itself when it has
     *  no parent (a cycle of parents stops where it comes round again). */
    default String root(String id) {
        Set<String> seen = new HashSet<>();
        String at = id;
        while (seen.add(at)) {
            String parent = findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || seen.contains(parent)) {
                break;
            }
            at = parent;
        }
        return at;
    }

    /** How many levels up a sheet's rates are taken from: its parent, its parent's parent, and so on. */
    int ANCESTOR_LEVELS = 3;

    /** The ids of a sheet's ancestors, nearest first: its parent, then the parent's parent, up to
     *  {@link #ANCESTOR_LEVELS} levels up; empty when it has no parent. */
    default List<String> ancestors(String id) {
        List<String> up = new ArrayList<>();
        String at = id;
        while (up.size() < ANCESTOR_LEVELS) {
            String parent = findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || parent.equals(id) || up.contains(parent)) {
                break;
            }
            up.add(parent);
            at = parent;
        }
        return up;
    }

    /** The ids of the sheets a sheet's rates are taken from when it has none in force, nearest first:
     *  its {@link #ancestors}, then, when it was cloned, the sheet it was cloned from and that sheet's
     *  own rate sources (its ancestors, then the sheet it was cloned from, and so on), each once and
     *  never the sheet itself. */
    default List<String> rateSources(String id) {
        List<String> sources = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String at = id;
        while (at != null && visited.add(at)) {
            for (String ancestor : ancestors(at)) {
                if (!ancestor.equals(id) && !sources.contains(ancestor)) {
                    sources.add(ancestor);
                }
            }
            at = findById(at).map(Sheet::getSourceId).orElse(null);
            if (at != null && !at.equals(id) && !sources.contains(at)) {
                sources.add(at);
            }
        }
        return sources;
    }
}
