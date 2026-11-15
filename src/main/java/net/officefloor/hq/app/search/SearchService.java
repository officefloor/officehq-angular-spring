package net.officefloor.hq.app.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds people by name or alias over a sheet and the other sheets of its tree. */
@Service
public class SearchService {

    /** A person found: the sheet they are on, their name and their due-total as the screen shows it. */
    public record Found(String sheetId, String name, String dueTotal) {
    }

    private final SheetService sheets;
    private final PersonRepository people;
    private final RollupService rollup;

    public SearchService(SheetService sheets, PersonRepository people, RollupService rollup) {
        this.sheets = sheets;
        this.people = people;
        this.rollup = rollup;
    }

    /** Everyone whose name or an alias contains {@code text} (ignoring case), each sheet's figures
     *  as they are now: the sheet's own people first in list order, then the other sheets' of its
     *  tree by sheet id. */
    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String key = text.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>(List.of(sheetId));
        ids.addAll(sheets.tree(sheetId).stream().sorted().toList());
        List<Found> out = new ArrayList<>();
        for (String id : ids) {
            List<Person> matching = people.findBySheetIdOrderByPositionAscIdAsc(id).stream()
                    .filter(p -> matches(p, key)).toList();
            if (matching.isEmpty()) {
                continue;
            }
            Map<Long, RollupService.Figures> figures = rollup.snapshot(id).people();
            for (Person p : matching) {
                RollupService.Figures f = figures.get(p.getId());
                out.add(new Found(id, p.getName(), RollupService.text(f == null ? null : f.dueTotal())));
            }
        }
        return out;
    }

    private static boolean matches(Person person, String key) {
        return contains(person.getName(), key) || person.getAliases().stream().anyMatch(a -> contains(a, key));
    }

    private static boolean contains(String value, String key) {
        return value.toLowerCase(Locale.ROOT).contains(key);
    }
}
