package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementSummary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/people/import")
@Validated
public class PersonImportController {

    /** The import box: lines of name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many people an import added and how many it updated, and those people as they now show,
     *  in line order. */
    public record Imported(int added, int updated, List<PersonView> people) {
    }

    private final PersonImportService service;
    private final PersonService persons;
    private final SheetService sheets;
    private final StatementService statements;

    public PersonImportController(PersonImportService service, PersonService persons, SheetService sheets,
            StatementService statements) {
        this.service = service;
        this.persons = persons;
        this.sheets = sheets;
        this.statements = statements;
    }

    @PostMapping
    public Imported importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm form) {
        String sheet = sheetId.trim();
        PersonImportService.Result result = service.importPeople(sheet, user.trim(), form.text());
        Map<Long, StatementSummary> summaries = statements.summaries(sheet);
        LocalDate today = sheets.today(sheet);
        Predicate<Person> clashing = persons.clashing(sheet);
        return new Imported(result.added().size(), result.updated().size(), result.people().stream()
                .map(p -> PersonView.of(p, summaries.getOrDefault(p.getId(), StatementSummary.NONE), today,
                        clashing.test(p)))
                .toList());
    }
}
