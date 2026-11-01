package net.officefloor.hq.app.search;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A search over a sheet's tree: the sheet at the top of its line of parents and all of that sheet's descendants. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/tree")
public class TreeSearchController {

    /** A person found: the sheet they are on, their name, their due-total as that sheet shows it, and their grade in
     *  force on that sheet's today. */
    public record Found(String sheet, String name, String dueTotal, String grade) {
    }

    private final SheetRepository sheets;
    private final PersonRepository people;
    private final Rollup rollup;

    public TreeSearchController(SheetRepository sheets, PersonRepository people, Rollup rollup) {
        this.sheets = sheets;
        this.people = people;
        this.rollup = rollup;
    }

    /** Every person on the sheet's tree whose name or an alias contains the text, case ignored (an empty text finds
     *  everyone): the sheet's own people first in list order, then the other sheets' by sheet id, each in its list
     *  order. Read from the store as it holds them now. */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public List<Found> search(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        List<String> ancestors = sheets.ancestorsOf(sheetId, Integer.MAX_VALUE);
        String top = ancestors.isEmpty() ? sheetId : ancestors.get(ancestors.size() - 1);
        Set<String> others = new HashSet<>(sheets.descendantsOf(top));
        others.add(top);
        others.remove(sheetId);
        List<String> order = new ArrayList<>(List.of(sheetId));
        others.stream().sorted().forEach(order::add);

        String wanted = text.trim().toLowerCase(Locale.ROOT);
        List<Found> found = new ArrayList<>();
        for (String id : order) {
            Rollup.Figures figures = rollup.of(id);
            LocalDate today = sheets.findById(id).map(Sheet::getToday).orElse(null);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(id)) {
                if (matches(person, wanted)) {
                    Rollup.PersonFigures f = figures.people().get(person.getId());
                    found.add(new Found(id, person.getName(), f == null ? "n/a" : f.dueTotal(),
                            person.gradeOn(today)));
                }
            }
        }
        return found;
    }

    private static boolean matches(Person person, String wanted) {
        return person.getName().toLowerCase(Locale.ROOT).contains(wanted)
                || person.getAliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(wanted));
    }
}
