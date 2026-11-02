package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    @Query("select s.lastClaimNumber from Sheet s where s.id = :id")
    int lastClaimNumber(String id);

    /** Lock the sheet's row until the transaction ends (a no-op update); 0 when there is no such sheet. */
    @Modifying
    @Query("update Sheet s set s.lastClaimNumber = s.lastClaimNumber where s.id = :id")
    int lockClaimNumber(String id);

    /** The last claim number given on any of the sheets. */
    @Query("select coalesce(max(s.lastClaimNumber), 0) from Sheet s where s.id in :ids")
    int lastClaimNumber(Collection<String> ids);

    /** Set the sheet's last claim number. */
    @Modifying
    @Query("update Sheet s set s.lastClaimNumber = :number where s.id = :id")
    int setLastClaimNumber(String id, int number);

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
}
