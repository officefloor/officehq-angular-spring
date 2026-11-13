package net.officefloor.hq.app.person;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonView(Long id, String name, String grade, long version) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion());
        }
    }

    /** A person's fields: a name that is not blank and a grade of A, B or C. */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** A person's fields as saved from an editor, with the version of the person the editor loaded. */
    public record PersonUpdate(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @NotNull Long version) {
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
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name(), request.grade()));
    }

    /** Change a person's name and grade; refused when the person was saved since the editor loaded them. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PersonUpdate request) {
        return PersonView.of(service.update(sheetId.trim(), SignedInUser.decode(user), id, request.version(),
                request.name(), request.grade()));
    }

    /** Someone else saved the person since the editor loaded them: the person as stored now. */
    @ExceptionHandler(StalePersonException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public PersonView stale(StalePersonException e) {
        return PersonView.of(e.getCurrent());
    }

    /** A save that raced another to the same version; the editor re-reads the person. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public void raced() {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    /** The name is already in use on the sheet; a concurrent save is caught by the unique index. */
    @ExceptionHandler({NameInUseException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
