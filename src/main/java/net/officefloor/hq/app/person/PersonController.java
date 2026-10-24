package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.server.ResponseStatusException;

/** The people on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    public record PersonView(long id, String name, String grade, long version) {
        public static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion());
        }
    }

    /** A person's name (not blank; kept without the spaces around it and unique on the sheet
     * without regard to case) and grade, one of A, B or C. */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version) {
    }

    /** A save refused because the person was saved by someone else since the editor loaded them:
     * the person as now stored. */
    public record StaleView(String error, PersonView current) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(PersonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
    }

    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        if (request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
        }
        try {
            return PersonView.of(service.update(sheetId.trim(), user.trim(), personId, request.version(),
                    request.name(), request.grade()));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another save of the person committed while this one was under way.
            throw new PersonStaleException(service.get(sheetId.trim(), personId));
        }
    }

    @ExceptionHandler(PersonStaleException.class)
    public ResponseEntity<StaleView> stale(PersonStaleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new StaleView("stale", PersonView.of(e.getCurrent())));
    }
}
