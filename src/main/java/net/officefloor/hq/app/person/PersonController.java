package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

    /** A person; {@code retiredFrom} is null while they are not retired. */
    public record PersonView(long id, String name, String grade, long version, boolean retired,
            LocalDate retiredFrom, boolean held) {
        public static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld());
        }
    }

    /** A patron a person has named. */
    public record PatronView(long id, long personId, long patronId) {
        public static PatronView of(PersonPatron patron) {
            return new PatronView(patron.getId(), patron.getPersonId(), patron.getPatronId());
        }
    }

    /** The person to be named as a patron. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    /** The date a person is retired from (the sheet's today when left out). */
    public record RetireRequest(LocalDate from) {
    }

    /** Whether the person is to be on hold (true) or taken off it (false). */
    public record HoldRequest(@NotNull Boolean held) {
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

    @GetMapping("/patrons")
    public List<PatronView> patrons(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.patrons(sheetId.trim()).stream().map(PatronView::of).toList();
    }

    @PostMapping("/{personId}/patrons")
    @ResponseStatus(HttpStatus.CREATED)
    public PatronView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return PatronView.of(service.addPatron(sheetId.trim(), user.trim(), personId, request.patronId()));
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

    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) RetireRequest request) {
        return PersonView.of(service.retire(sheetId.trim(), user.trim(), personId,
                request == null ? null : request.from()));
    }

    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid HoldRequest request) {
        return PersonView.of(service.hold(sheetId.trim(), user.trim(), personId, request.held()));
    }

    @ExceptionHandler(PersonStaleException.class)
    public ResponseEntity<StaleView> stale(PersonStaleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new StaleView("stale", PersonView.of(e.getCurrent())));
    }
}
