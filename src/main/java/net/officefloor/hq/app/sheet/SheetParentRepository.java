package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetParentRepository extends JpaRepository<SheetParent, String> {

    /** The sheet's parent; null when it names none. */
    default String parentOf(String sheetId) {
        return findById(sheetId).map(SheetParent::getParentSheet).orElse(null);
    }

    /** The sheet's parent, its parent's parent and so on, nearest first, at most {@code levels} of them. */
    default List<String> ancestorsOf(String sheetId, int levels) {
        List<String> ancestors = new ArrayList<>();
        String at = sheetId;
        while (ancestors.size() < levels) {
            at = parentOf(at);
            if (at == null || at.equals(sheetId) || ancestors.contains(at)) break;
            ancestors.add(at);
        }
        return ancestors;
    }
}
