package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonView(long id, String name, Grade grade) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade());
        }
    }

    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade) {}

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people, in the order they were added. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.list(sheetId).stream().map(PersonView::of).toList();
    }

    /** Add a person. */
    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.add(sheetId, user, form.name(), form.grade()));
    }

    /** Change a person's name and grade. */
    @PutMapping("/{personId}")
    public PersonView edit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.edit(sheetId, personId, form.name(), form.grade()));
    }
}
