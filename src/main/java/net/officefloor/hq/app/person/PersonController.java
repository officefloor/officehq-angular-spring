package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /** What the screen shows of a person. */
    public record PersonView(long id, String name, Grade grade, long version) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion());
        }
    }

    /** A person's fields, as entered on the form. */
    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade) {
    }

    /** A person's fields as edited, with the version of the person the editor loaded. */
    public record PersonEdit(@NotBlank @Size(max = 200) String name, @NotNull Grade grade, @NotNull Long version) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PersonView::of).toList();
    }

    /** Add a person. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonForm form) {
        return PersonView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.grade()));
    }

    /** Edit a person. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonEdit form) {
        return PersonView.of(service.update(sheetId.trim(), SignedInUser.name(user), id, form.version(),
                form.name(), form.grade()));
    }
}
