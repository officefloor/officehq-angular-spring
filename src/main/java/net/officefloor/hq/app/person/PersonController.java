package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people kept on a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {}

    public record PersonView(Long id, String name, String grade) {

        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade());
        }
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.list(sheetId).stream().map(PersonView::of).toList();
    }

    @PostMapping
    public PersonView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.add(user, sheetId, form.name(), form.grade()));
    }

    @PutMapping("/{id}")
    public PersonView update(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.update(user, sheetId, id, form.name(), form.grade()));
    }
}
