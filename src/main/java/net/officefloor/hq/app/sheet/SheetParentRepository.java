package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetParentRepository extends JpaRepository<SheetParent, String> {

    /** The links of the sheets naming the sheet as their parent. */
    List<SheetParent> findByParentSheet(String parentSheet);

    /** The sheet's children, their children and so on, each once, never the sheet itself. */
    default List<String> descendantsOf(String sheetId) {
        List<String> descendants = new ArrayList<>();
        for (int i = -1; i < descendants.size(); i++) {
            String at = i < 0 ? sheetId : descendants.get(i);
            for (SheetParent link : findByParentSheet(at)) {
                String child = link.getChildSheet();
                if (!child.equals(sheetId) && !descendants.contains(child)) descendants.add(child);
            }
        }
        return descendants;
    }

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
