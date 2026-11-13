package net.officefloor.hq.app.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds people over a sheet and every other sheet of its tree (its ancestors and their descendants) by
 * a piece of their name or an alias, case ignored. It reads; it records nothing.
 */
@Service
public class TreeSearchService {

    /** A person found: their sheet, their name and their due-total as their sheet shows it (null for n/a). */
    public record Found(String sheetId, String name, BigDecimal dueTotal) {
    }

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Rollup rollup;

    public TreeSearchService(PersonRepository people, SheetRepository sheets, Rollup rollup) {
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
    }

    /** Everyone whose name or an alias contains the text (blank matches everyone): the sheet's own people
     *  first in list order, then the other sheets' of its tree, by sheet id, each in list order. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String key = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Found> found = new ArrayList<>();
        List<String> order = new ArrayList<>(List.of(sheetId));
        sheets.tree(sheetId).stream().filter(s -> !s.equals(sheetId)).sorted().forEach(order::add);
        for (String sheet : order) {
            Rollup.Snapshot snapshot = rollup.take(sheet);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheet)) {
                if (matches(person, key)) {
                    Rollup.Figures figures = snapshot.people().get(person.getId());
                    found.add(new Found(sheet, person.getName(), figures == null ? null : figures.dueTotal()));
                }
            }
        }
        return found;
    }

    private static boolean matches(Person person, String key) {
        if (person.getName().toLowerCase(Locale.ROOT).contains(key)) {
            return true;
        }
        return person.getAliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(key));
    }
}
