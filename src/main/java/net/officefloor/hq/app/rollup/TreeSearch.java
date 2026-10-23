package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people of a sheet's whole tree (its root and all the root's descendants) whose name or an alias
 * contains a text, case ignored, each with their due-total as their own sheet shows it now.
 */
@Service
public class TreeSearch {

    /** A person found: their sheet, name and due-total (null when n/a). */
    public record Found(String sheetId, String name, BigDecimal dueTotal) {
    }

    private final SheetService sheets;
    private final PersonRepository people;
    private final Rollups rollups;

    public TreeSearch(SheetService sheets, PersonRepository people, Rollups rollups) {
        this.sheets = sheets;
        this.people = people;
        this.rollups = rollups;
    }

    /** The people found: this sheet's first in list order, then the other sheets' by sheet id. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String key = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<String> others = new ArrayList<>(sheets.tree(sheetId));
        others.remove(sheetId);
        others.sort(null);
        List<String> order = new ArrayList<>();
        order.add(sheetId);
        order.addAll(others);
        List<Found> found = new ArrayList<>();
        for (String id : order) {
            Rollups.Rollup rollup = rollups.take(id);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                if (matches(person, key)) {
                    Rollups.Figures figures = rollup.people().get(person.getId());
                    found.add(new Found(id, person.getName(), figures == null ? null : figures.dueTotal()));
                }
            }
        }
        return found;
    }

    private static boolean matches(Person person, String key) {
        if (person.getName().toLowerCase(Locale.ROOT).contains(key)) {
            return true;
        }
        return person.getAliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(key));
    }
}
