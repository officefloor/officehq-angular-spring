package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /** What the screen shows of a person. */
    public record PersonView(long id, String name, String grade, long version) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion());
        }
    }

    /** The person form: a required name and a grade of A, B or C. An edit also gives the version
     *  of the person its editor loaded. */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, Long version) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PersonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), form.name().trim(), form.grade()));
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm form) {
        if (form.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The version of the person edited is required");
        }
        return PersonView.of(service.edit(sheetId.trim(), user.trim(), personId, form.version(), form.name().trim(),
                form.grade()));
    }
}
