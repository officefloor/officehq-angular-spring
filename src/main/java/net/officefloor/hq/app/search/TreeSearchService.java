package net.officefloor.hq.app.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds people by name or alias across a sheet and the other sheets of its tree. */
@Service
public class TreeSearchService {

    /** A person found: the sheet they are on, their name and their due-total there ("n/a" for none). */
    public record Found(String sheetId, String name, String dueTotal) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final RollupService rollups;
    private final SheetService sheets;

    public TreeSearchService(PersonRepository people, PersonService personService, RollupService rollups,
            SheetService sheets) {
        this.people = people;
        this.personService = personService;
        this.rollups = rollups;
        this.sheets = sheets;
    }

    /** Every person whose name or an alias contains the text, case ignored: the sheet's own in list
     *  order first, then those of the other sheets of its tree by sheet id. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<String> others = new ArrayList<>(sheets.tree(sheetId));
        others.sort(null);
        List<String> order = new ArrayList<>(List.of(sheetId));
        order.addAll(others);
        List<Found> found = new ArrayList<>();
        for (String sheet : order) {
            Map<Long, List<String>> aliases = personService.aliases(sheet, null);
            Map<Long, String> dues = rollups.dueTotals(sheet);
            for (Person person : people.findBySheetIdOrderByIdAsc(sheet)) {
                if (contains(person.getName(), needle) || aliases.getOrDefault(person.getId(), List.of()).stream()
                        .anyMatch(alias -> contains(alias, needle))) {
                    found.add(new Found(sheet, person.getName(), dues.getOrDefault(person.getId(), "n/a")));
                }
            }
        }
        return found;
    }

    private static boolean contains(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
