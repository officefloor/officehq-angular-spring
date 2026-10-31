package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.person.PersonController.PersonView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The import box for people. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people/import")
public class PersonImportController {

    /** The pasted text: one person per line, as name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** What an import did: how many people it added and the people, in the order added. */
    public record ImportView(int added, List<PersonView> people) {
    }

    private final PersonImportService imports;
    private final PersonService people;

    public PersonImportController(PersonImportService imports, PersonService people) {
        this.imports = imports;
        this.people = people;
    }

    @PostMapping
    public ImportView importPeople(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm request) {
        List<Person> added = imports.importPeople(sheetId, user, request.text());
        var today = people.today(sheetId);
        var used = people.usedInTree(sheetId);
        List<PersonView> views = added.stream().map(p -> PersonView.of(p, today, PersonService.clashes(p, used))).toList();
        return new ImportView(views.size(), views);
    }
}
