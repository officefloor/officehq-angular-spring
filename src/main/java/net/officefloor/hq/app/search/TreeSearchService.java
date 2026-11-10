package net.officefloor.hq.app.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searching a sheet's tree (the sheet and every other sheet of its tree, see {@link SheetService#tree})
 * for the people whose name or an alias contains a text, case ignored: the sheet's people first in
 * list order, then the other sheets' by sheet id, each sheet's in its list order. Each comes with
 * their due-total as their own sheet shows it now. It only reads.
 */
@Service
public class TreeSearchService {

    /** A person found: the sheet they are on, their name and their due-total (n/a for none). */
    public record Found(String sheet, String name, String dueTotal) {
    }

    private final SheetService sheets;
    private final PersonRepository people;
    private final RollupService rollup;

    public TreeSearchService(SheetService sheets, PersonRepository people, RollupService rollup) {
        this.sheets = sheets;
        this.people = people;
        this.rollup = rollup;
    }

    @Transactional(readOnly = true)
    public List<Found> search(String sheetId, String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        ids.add(sheetId);
        ids.addAll(sheets.tree(sheetId).stream().sorted().toList());
        List<Found> found = new ArrayList<>();
        for (String id : ids) {
            RollupService.Snapshot figures = null;
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                if (!matches(person, wanted)) {
                    continue;
                }
                if (figures == null) {
                    figures = rollup.snapshot(id);
                }
                RollupService.Figures own = figures.people().get(person.getId());
                found.add(new Found(id, person.getName(), money(own == null ? null : own.dueTotal())));
            }
        }
        return found;
    }

    private static boolean matches(Person person, String wanted) {
        return person.getName().toLowerCase(Locale.ROOT).contains(wanted)
                || person.getAliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(wanted));
    }

    private static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
