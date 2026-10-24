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

    /** A grade a person was given and the date it is in force from (null: the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
        public static GradeView of(PersonGrade grade) {
            return new GradeView(grade.getGrade(), grade.getFrom());
        }
    }

    /** A person: the grade in force on the sheet's {@code today}, and every grade they were given by
     * from-date; {@code retiredFrom} is null while they are not retired. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held) {
        public static PersonView of(Person person, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(GradeView::of).toList(), person.getVersion(),
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
     * without regard to case) and grade, one of A, B or C, given from {@code gradeFrom} on a save (the
     * sheet's today when left out; a person's first grade applies from the beginning of time). */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
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
        LocalDate today = service.today(sheetId.trim());
        return service.list(sheetId.trim()).stream().map(person -> PersonView.of(person, today)).toList();
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
        return view(sheetId, service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
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
            return view(sheetId, service.update(sheetId.trim(), user.trim(), personId, request.version(),
                    request.name(), request.grade(), request.gradeFrom()));
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
        return view(sheetId, service.retire(sheetId.trim(), user.trim(), personId,
                request == null ? null : request.from()));
    }

    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid HoldRequest request) {
        return view(sheetId, service.hold(sheetId.trim(), user.trim(), personId, request.held()));
    }

    @ExceptionHandler(PersonStaleException.class)
    public ResponseEntity<StaleView> stale(PersonStaleException e) {
        Person current = e.getCurrent();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new StaleView("stale", PersonView.of(current, service.today(current.getSheetId()))));
    }

    /** The person as the sheet shows them on its today. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId.trim()));
    }
}
