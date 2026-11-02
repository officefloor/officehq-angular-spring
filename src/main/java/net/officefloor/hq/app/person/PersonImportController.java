package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** How many people an import added, and those people as they now show, in order. */
    public record Imported(int added, List<PersonView> people) {
    }

    private final PersonImportService service;
    private final PersonService persons;
    private final SheetService sheets;

    public PersonImportController(PersonImportService service, PersonService persons, SheetService sheets) {
        this.service = service;
        this.persons = persons;
        this.sheets = sheets;
    }

    @PostMapping
    public Imported importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm form) {
        String sheet = sheetId.trim();
        List<Person> added = service.importPeople(sheet, user.trim(), form.text());
        LocalDate today = sheets.today(sheet);
        Predicate<Person> clashing = persons.clashing(sheet);
        return new Imported(added.size(), added.stream()
                .map(p -> PersonView.of(p, StatementSummary.NONE, today, clashing.test(p)))
                .toList());
    }
}
