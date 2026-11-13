package net.officefloor.hq.app.person;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonView(Long id, String name, String grade) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade());
        }
    }

    /** A person's fields: a name that is not blank and a grade of A, B or C. */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PersonView::of).toList();
    }

    /** Add a person to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), request.name(), request.grade()));
    }

    /** Change a person's name and grade. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.update(sheetId.trim(), id, request.name(), request.grade()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }
}
