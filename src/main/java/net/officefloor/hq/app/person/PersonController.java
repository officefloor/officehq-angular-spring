package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

/** The people on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /** A person as the screen shows it, with how many statements were taken of them and the last one's
     *  due-total (n/a when it was) and date; none of either before their first statement. */
    public record PersonView(long id, String name, String grade, long version, int statementCount,
            String statedTotal, String statementDate, boolean retired, String retiredFrom,
            boolean held) {

        public static PersonView of(Person person, List<Statement> statements) {
            Statement last = statements.isEmpty() ? null : statements.get(statements.size() - 1);
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    statements.size(),
                    last == null ? null : last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString(),
                    last == null ? null : last.getTakenOn().toString(),
                    person.isRetired(), person.getRetiredFrom() == null ? null : person.getRetiredFrom().toString(),
                    person.isHeld());
        }
    }

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** A person's new name and grade, with the version of the person the editor loaded. */
    public record PersonUpdate(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @NotNull Long version) {
    }

    /** The date a person is retired from; none takes the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        Map<Long, List<Statement>> statements = service.statements(sheetId.trim());
        return service.list(sheetId.trim()).stream()
                .map(p -> PersonView.of(p, statements.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a person to the sheet. */
    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.grade()), List.of());
    }

    /** Change a person's name and grade. */
    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonUpdate request) {
        try {
            Person updated = service.update(sheetId.trim(), user.trim(), personId, request.version(),
                    request.name(), request.grade());
            return PersonView.of(updated, service.statementsOf(updated.getId()));
        } catch (ObjectOptimisticLockingFailureException e) {
            // saved by someone else between the version check and the write
            throw new PersonVersionConflictException(service.current(sheetId.trim(), personId));
        }
    }

    /** Retire a person from a date. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody RetireRequest request) {
        Person retired = service.retire(sheetId.trim(), user.trim(), personId, request.from());
        return PersonView.of(retired, service.statementsOf(retired.getId()));
    }

    /** Put a person on hold, or take them off it. */
    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        Person toggled = service.toggleHold(sheetId.trim(), user.trim(), personId);
        return PersonView.of(toggled, service.statementsOf(toggled.getId()));
    }

    /** A save from an editor that loaded an older version: refused, answering the person as it is now. */
    @ExceptionHandler(PersonVersionConflictException.class)
    public ResponseEntity<PersonView> conflict(PersonVersionConflictException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(PersonView.of(e.getCurrent(), service.statementsOf(e.getCurrent().getId())));
    }
}
