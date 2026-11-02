package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people of a sheet and every other sheet of its tree (its topmost ancestor and every descendant
 * of that), for the tree's CSV export: the sheet's own people first in list order, then the other
 * sheets' by sheet id, each with the grade in force on its sheet's today and the due-total its sheet
 * shows for them (n/a when it has none).
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/tree-export")
public class TreeExportController {

    /** One person of the tree. */
    public record Line(String sheet, String name, String grade, String dueTotal) {
    }

    private final SheetParentRepository parents;
    private final PersonRepository people;
    private final RollupService rollup;
    private final PersonService personService;

    public TreeExportController(SheetParentRepository parents, PersonRepository people, RollupService rollup,
            PersonService personService) {
        this.parents = parents;
        this.people = people;
        this.rollup = rollup;
        this.personService = personService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Line> export(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        List<String> sheets = new ArrayList<>(List.of(sheetId));
        parents.treeOf(sheetId).stream().filter(at -> !at.equals(sheetId)).sorted().forEach(sheets::add);
        List<Line> lines = new ArrayList<>();
        for (String at : sheets) {
            RollupService.Snapshot figures = rollup.current(at);
            LocalDate today = personService.today(at);
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(at)) {
                RollupService.Figures own = figures.people().get(person.getId());
                BigDecimal due = own == null ? null : own.dueTotal();
                String grade = today == null ? person.getGrade() : person.gradeOn(today);
                lines.add(new Line(at, person.getName(), grade, due == null ? "n/a" : due.setScale(2).toPlainString()));
            }
        }
        return lines;
    }
}
