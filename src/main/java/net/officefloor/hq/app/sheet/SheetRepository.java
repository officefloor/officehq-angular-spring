package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked until the transaction ends, so two users' claim numbers go one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findForUpdateById(String id);

    /** The sheets naming this one as their parent: its children. */
    List<Sheet> findByParentOrderByIdAsc(String parent);

    /** How many levels up a sheet's rates are taken from its ancestors. */
    int ANCESTOR_LEVELS = 3;

    /** The sheet's parent, the parent's parent and so on, nearest first, up to three levels up. */
    default List<String> ancestors(String id) {
        List<String> found = new ArrayList<>();
        String at = id;
        while (found.size() < ANCESTOR_LEVELS) {
            String parent = findById(at).map(Sheet::getParent).orElse(null);
            if (parent == null || parent.equals(id) || found.contains(parent)) {
                break;
            }
            found.add(parent);
            at = parent;
        }
        return found;
    }

    /** The other sheets of the sheet's tree: all its ancestors, however far up, and all its descendants and
     *  theirs, among the sheets that exist. */
    default Set<String> tree(String id) {
        Set<String> found = new LinkedHashSet<>();
        String at = findById(id).map(Sheet::getParent).orElse(null);
        while (at != null && !at.equals(id) && found.add(at)) {
            at = findById(at).map(Sheet::getParent).orElse(null);
        }
        Deque<String> next = new ArrayDeque<>(List.of(id));
        while (!next.isEmpty()) {
            for (Sheet child : findByParentOrderByIdAsc(next.pop())) {
                if (!child.getId().equals(id) && found.add(child.getId())) {
                    next.add(child.getId());
                }
            }
        }
        found.removeIf(s -> !existsById(s));
        return found;
    }
}
