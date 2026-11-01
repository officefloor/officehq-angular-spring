package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** The topmost ancestor of the sheet (the sheet itself when it names no parent). */
    default String rootOf(String sheetId) {
        String root = sheetId;
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        for (String at = parentOf(sheetId); at != null && seen.add(at); at = parentOf(at)) root = at;
        return root;
    }

    /** Every sheet of the sheet's tree: its topmost ancestor, then every descendant of that, each once. */
    default List<String> treeOf(String sheetId) {
        String root = rootOf(sheetId);
        List<String> tree = new ArrayList<>(List.of(root));
        tree.addAll(descendantsOf(root));
        return tree;
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
