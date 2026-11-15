package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/import-people")
@Validated
public class ImportPeopleController {

    /** The import box's text: lines of name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 1_000_000) String text) {
    }

    private final ImportPeopleService service;
    private final PersonService people;

    public ImportPeopleController(ImportPeopleService service, PersonService people) {
        this.service = service;
        this.people = people;
    }

    /** The people added, in the order of their lines. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<PersonView> importPeople(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm body) {
        List<Person> added = service.importPeople(sheetId, user.trim(), body.text());
        LocalDate today = people.today(sheetId);
        return added.stream()
                .map(p -> PersonView.of(p, today, StatementSummary.NONE, List.of(), false, people.clashing(p)))
                .toList();
    }
}
