package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people of a sheet's tree (its top sheet, found up its parents, and all the top sheet's
 * descendants) whose name or an alias contains a text, case ignored: the sheet's own people first in
 * list order, then the other sheets' by sheet id, each shown as {@code <name> (<grade>)} with the grade
 * in force on their sheet's today, and with their due-total as their sheet shows it.
 */
@Service
public class TreeSearchService {

    /** A person found: their sheet, name with their grade in force on that sheet's today, and due-total with two decimals, or "n/a". */
    public record Found(String sheetId, String name, String dueTotal) {
    }

    private final SheetParentRepository parents;
    private final PersonRepository people;
    private final Rollup rollup;
    private final SheetService sheets;

    public TreeSearchService(SheetParentRepository parents, PersonRepository people, Rollup rollup,
            SheetService sheets) {
        this.parents = parents;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
    }

    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String needle = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<Found> found = new ArrayList<>(searchSheet(sheetId, needle));
        for (String other : treeOf(sheetId)) {
            if (!other.equals(sheetId)) found.addAll(searchSheet(other, needle));
        }
        return found;
    }

    private List<Found> searchSheet(String sheetId, String needle) {
        Rollup.Snapshot figures = rollup.snapshot(sheetId);
        List<Found> found = new ArrayList<>();
        LocalDate today = null;
        for (Person person : people.findInListOrder(sheetId)) {
            if (!matches(person, needle)) continue;
            Rollup.Figures theirs = figures.people().get(person.getId());
            BigDecimal due = theirs == null ? null : theirs.dueTotal();
            if (today == null) today = sheets.today(sheetId);
            found.add(new Found(sheetId, person.getName() + " (" + person.gradeOn(today) + ")",
                    due == null ? "n/a" : due.setScale(2, RoundingMode.HALF_EVEN).toPlainString()));
        }
        return found;
    }

    private static boolean matches(Person person, String needle) {
        if (person.getName().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return person.getAliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(needle));
    }

    /** The sheets of the sheet's tree, by id. */
    private Set<String> treeOf(String sheetId) {
        String top = sheetId;
        Set<String> up = new HashSet<>(List.of(sheetId));
        for (String parent = parentOf(top); parent != null && up.add(parent); parent = parentOf(parent)) {
            top = parent;
        }
        Set<String> tree = new TreeSet<>(List.of(top));
        Deque<String> next = new ArrayDeque<>(List.of(top));
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.pop())) {
                if (tree.add(child.getSheetId())) next.add(child.getSheetId());
            }
        }
        return tree;
    }

    private String parentOf(String id) {
        return parents.findById(id).map(SheetParentLink::getParentId).orElse(null);
    }
}
