package net.officefloor.hq.app.person;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The names and aliases used across a sheet's tree: the sheet's ancestors, and every sheet below its
 * topmost ancestor, among the sheets that exist. A name or alias one of them uses may not be used on the
 * sheet; a person whose name or alias one of them uses clashes. */
@Service
public class PersonTree {

    private final SheetRepository sheets;
    private final PersonRepository people;

    public PersonTree(SheetRepository sheets, PersonRepository people) {
        this.sheets = sheets;
        this.people = people;
    }

    /** The ids of the other sheets of the sheet's tree. */
    @Transactional(readOnly = true)
    public Set<String> othersOf(String sheetId) {
        Set<String> seen = new LinkedHashSet<>();
        String root = sheetId;
        for (Sheet up = sheets.findById(sheetId).orElse(null); up != null && seen.add(up.getId());
                up = up.getParentId() == null ? null : sheets.findById(up.getParentId()).orElse(null)) {
            root = up.getId();
        }
        Set<String> tree = new LinkedHashSet<>(seen);
        Deque<String> below = new ArrayDeque<>(List.of(root));
        while (!below.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(below.pop())) {
                if (tree.add(child.getId())) below.add(child.getId());
            }
        }
        tree.remove(sheetId);
        return tree;
    }

    /** Whether another sheet of the sheet's tree uses {@code name} as a name or alias (as names are
     * compared). */
    @Transactional(readOnly = true)
    public boolean taken(String sheetId, String name) {
        return taken(othersOf(sheetId), name);
    }

    /** Whether the person's name or any alias of theirs is used on another sheet of their tree. */
    @Transactional(readOnly = true)
    public boolean clashes(Person person) {
        Set<String> others = othersOf(person.getSheetId());
        return taken(others, person.getName()) || person.getAliases().stream().anyMatch(a -> taken(others, a));
    }

    /** Refuses {@code name} when another sheet of the sheet's tree uses it. */
    void requireFree(String sheetId, String name) {
        if (taken(sheetId, name)) throw new PersonNameTakenException();
    }

    private boolean taken(Set<String> others, String name) {
        if (others.isEmpty()) return false;
        String key = Person.keyOf(name);
        return people.existsBySheetIdInAndNameKey(others, key) || people.existsAliasInSheets(others, key);
    }
}
