package net.officefloor.hq.app.person;

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

/** People on a sheet. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonView(Long id, String name, String grade) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade());
        }
    }

    /** The name is stored trimmed; one that is empty or only spaces is refused. */
    public record PersonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
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
            @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
    }

    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.update(sheetId.trim(), user.trim(), personId, request.name(), request.grade()));
    }
}
