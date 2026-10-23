package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked for the rest of the transaction so only one change at a time takes its next number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findLockedById(String id);

    /** A sheet's ancestors, nearest first: its parent, its parent's parent, and so on. */
    default List<String> ancestors(String id) {
        List<String> chain = new ArrayList<>();
        String next = findById(id).map(Sheet::getParentId).orElse(null);
        while (next != null && !next.equals(id) && !chain.contains(next)) {
            chain.add(next);
            next = findById(next).map(Sheet::getParentId).orElse(null);
        }
        return chain;
    }

    /**
     * The sheets whose rates a grade with no rate in force on this sheet falls back to, in order: its
     * ancestors (up to so many levels), then the sheet it was cloned from, that sheet's ancestors, the
     * sheet that one was cloned from, and so on; each sheet once, never this one.
     */
    default List<String> rateSources(String id, int parentLevels) {
        List<String> chain = new ArrayList<>();
        String sheet = id;
        while (sheet != null) {
            for (String ancestor : ancestors(sheet).stream().limit(parentLevels).toList()) {
                if (!ancestor.equals(id) && !chain.contains(ancestor)) {
                    chain.add(ancestor);
                }
            }
            String source = findById(sheet).map(Sheet::getClonedFrom).orElse(null);
            if (source == null || source.equals(id) || chain.contains(source)) {
                break;
            }
            chain.add(source);
            sheet = source;
        }
        return chain;
    }

    /** The sheets naming this one as their parent. */
    List<Sheet> findByParentIdOrderByIdAsc(String parentId);

    /** A sheet's descendants: its children, their children, and so on, each once. */
    default List<String> descendants(String id) {
        List<String> found = new ArrayList<>();
        List<String> next = new ArrayList<>(List.of(id));
        while (!next.isEmpty()) {
            String parent = next.remove(0);
            for (Sheet child : findByParentIdOrderByIdAsc(parent)) {
                if (!child.getId().equals(id) && !found.contains(child.getId())) {
                    found.add(child.getId());
                    next.add(child.getId());
                }
            }
        }
        return found;
    }
}
