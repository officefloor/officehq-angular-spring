package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Another sheet's summary due-totals per grade, for the screen to compare its own with: per grade, the
 * due-totals of the people on it on that sheet's today (retired people left out, an n/a due-total no
 * figure) added up. A sheet that does not exist gives no figures.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/compare")
public class CompareController {

    /** The grades the figures are given per. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    /** Whether the other sheet exists and, when it does, its due-total per grade with two decimals. */
    public record CompareView(boolean exists, Map<String, String> gradeDueTotals) {}

    private final RollupService rollup;
    private final PersonRepository persons;
    private final SheetRepository sheets;

    public CompareController(RollupService rollup, PersonRepository persons, SheetRepository sheets) {
        this.rollup = rollup;
        this.persons = persons;
        this.sheets = sheets;
    }

    @GetMapping("/{otherId}")
    @Transactional(readOnly = true)
    public CompareView compare(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String otherId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Optional<Sheet> other = sheets.findById(otherId);
        if (other.isEmpty()) return new CompareView(false, Map.of());
        LocalDate today = other.get().getToday();
        RollupService.Snapshot figures = rollup.current(otherId);
        Map<String, BigDecimal> gradeDues = new LinkedHashMap<>();
        for (String grade : GRADES) gradeDues.put(grade, BigDecimal.ZERO.setScale(2));
        for (Person person : persons.findBySheetIdOrderByPositionAscIdAsc(otherId)) {
            if (person.isRetired()) continue;
            RollupService.Figures theirs = figures.people().get(person.getId());
            if (theirs == null || theirs.dueTotal() == null) continue;
            String grade = today == null ? person.getGrade() : person.gradeOn(today);
            gradeDues.computeIfPresent(grade, (g, total) -> total.add(theirs.dueTotal()));
        }
        Map<String, String> gradeDueTotals = new LinkedHashMap<>();
        gradeDues.forEach((grade, total) -> gradeDueTotals.put(grade, total.setScale(2).toPlainString()));
        return new CompareView(true, gradeDueTotals);
    }
}
