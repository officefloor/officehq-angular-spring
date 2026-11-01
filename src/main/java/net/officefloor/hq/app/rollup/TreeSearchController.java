package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people of a sheet and every other sheet of its tree (its topmost ancestor and every descendant
 * of that) whose name or an alias contains the text, ignoring case: one line each, the sheet's own
 * people first in list order, then the other sheets' by sheet id, each with the due-total its sheet
 * shows for them (n/a when it has none).
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/tree-search")
public class TreeSearchController {

    private final SheetParentRepository parents;
    private final PersonRepository people;
    private final RollupService rollup;

    public TreeSearchController(SheetParentRepository parents, PersonRepository people, RollupService rollup) {
        this.parents = parents;
        this.people = people;
        this.rollup = rollup;
    }

    /** Lines of {@code <sheet id> <name> <due-total>}. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<String> search(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(name = "text", required = false, defaultValue = "") @Size(max = 200) String text) {
        String needle = text.strip().toLowerCase(Locale.ROOT);
        List<String> sheets = new ArrayList<>(List.of(sheetId));
        parents.treeOf(sheetId).stream().filter(at -> !at.equals(sheetId)).sorted().forEach(sheets::add);
        List<String> lines = new ArrayList<>();
        for (String at : sheets) {
            RollupService.Snapshot figures = rollup.current(at);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(at)) {
                boolean matches = person.namesAndAliases().stream()
                        .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(needle));
                if (!matches) continue;
                RollupService.Figures own = figures.people().get(person.getId());
                BigDecimal due = own == null ? null : own.dueTotal();
                lines.add(at + " " + person.getName() + " " + (due == null ? "n/a" : due.setScale(2).toPlainString()));
            }
        }
        return lines;
    }
}
