package net.officefloor.hq.app.person;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The names and aliases used across a sheet's tree: its ancestors, and its descendants and theirs, among the
 *  sheets that exist. A name or alias used on one sheet of the tree is not to be used on another. */
@Component
public class NameTree {

    /** No person is left out. */
    public static final long NO_ONE = -1L;

    private final SheetParentRepository parents;
    private final SheetRepository sheets;
    private final PersonRepository people;

    public NameTree(SheetParentRepository parents, SheetRepository sheets, PersonRepository people) {
        this.parents = parents;
        this.sheets = sheets;
        this.people = people;
    }

    /** The other sheets of the sheet's tree: its ancestors, nearest first, then its descendants. */
    @Transactional(readOnly = true)
    public List<String> othersOf(String sheetId) {
        Set<String> tree = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        seen.add(sheetId);
        for (String at = SettingService.parentOf(parents, sheetId); at != null && seen.add(at);
                at = SettingService.parentOf(parents, at)) {
            tree.add(at);
        }
        Deque<String> next = new ArrayDeque<>();
        next.add(sheetId);
        Set<String> visited = new HashSet<>();
        visited.add(sheetId);
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.poll())) {
                if (visited.add(child.getSheetId())) {
                    tree.add(child.getSheetId());
                    next.add(child.getSheetId());
                }
            }
        }
        tree.remove(sheetId);
        return tree.stream().filter(sheets::existsById).toList();
    }

    /** The keys of the names and aliases used on the other sheets of the sheet's tree, leaving out the
     *  person with the id given (none: {@link #NO_ONE}). */
    @Transactional(readOnly = true)
    public Set<String> keysElsewhere(String sheetId, long exceptId) {
        List<String> others = othersOf(sheetId);
        Set<String> keys = new HashSet<>();
        if (others.isEmpty()) return keys;
        keys.addAll(people.nameKeysOn(others, exceptId));
        keys.addAll(people.aliasKeysOn(others, exceptId));
        return keys;
    }

    /** Whether the name, as names are compared, is used on another sheet of the sheet's tree. */
    @Transactional(readOnly = true)
    public boolean usedElsewhere(String sheetId, String name) {
        return keysElsewhere(sheetId, NO_ONE).contains(Person.keyOf(name));
    }

    /** Whether the person's name or any alias is used on another sheet of their sheet's tree. */
    @Transactional(readOnly = true)
    public boolean clashes(Person person) {
        return clashes(person, keysElsewhere(person.getSheetId(), NO_ONE));
    }

    /** Whether the person's name or any alias is among the keys given. */
    public static boolean clashes(Person person, Set<String> keys) {
        return !keys.isEmpty() && (keys.contains(Person.keyOf(person.getName()))
                || person.getAliases().stream().map(Person::keyOf).anyMatch(keys::contains));
    }
}
