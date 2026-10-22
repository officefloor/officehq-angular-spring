package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /**
     * What the screen is told about a person, with their statement count, and the last statement's
     * due-total (null when n/a or none taken) and date (null when none taken), and the date they are
     * retired from (null when not retired), whether they have any hold, their holds, and the ids of their patrons.
     * Their grades are listed by the date each is in force from, the first with none (from the
     * beginning of time).
     */
    public record PersonView(long id, String name, List<GradeView> grades, long version, int statementCount,
            String statementTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held,
            List<HoldView> holds, List<Long> patronIds) {

        public static PersonView of(Person person) {
            List<GradeView> grades = new ArrayList<>();
            grades.add(new GradeView(person.getGrade(), null));
            person.getGradesFrom().forEach((from, grade) -> grades.add(new GradeView(grade, from)));
            return new PersonView(person.getId(), person.getName(), grades, person.getVersion(),
                    person.getStatementCount(),
                    person.getStatementTotal() == null ? null : person.getStatementTotal().toPlainString(),
                    person.getStatementDate(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(hold -> new HoldView(hold.getFromDate(), hold.getToDate())).toList(),
                    person.getPatronIds().stream().sorted().toList());
        }
    }

    /** A hold on a person, from a date to a date (null: no end). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /** A grade a person has, from a date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
    }

    /**
     * A person's values as entered on the form: a name is required, the grade is A, B or C. On an edit
     * the grade is recorded from its date (none: the sheet's today); a person's first grade applies
     * from the beginning of time. An edit carries the version of the person the form loaded.
     */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            Long version) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(PersonView::of).toList();
    }

    /** Add a person to the sheet. */
    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonForm form) {
        return PersonView.of(service.add(sheetId, SignedIn.name(user), form.name(), form.grade()));
    }

    /** Change a person's name and record their grade from its date, from the version of the person the form loaded. */
    @PutMapping("/{personId}")
    public PersonView edit(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonForm form) {
        if (form.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The version of the person edited is required.");
        }
        return PersonView.of(service.edit(sheetId, SignedIn.name(user), personId, form.version(), form.name(),
                form.grade(), form.gradeFrom()));
    }

    /** The date a person is retired from; with none, the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** Retire a person from a date. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody(required = false) RetireForm form) {
        return PersonView.of(service.retire(sheetId, SignedIn.name(user), personId,
                form == null ? null : form.from()));
    }

    /** A hold's dates: from (none: the sheet's today) and to (none: no end). */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** Put a person on hold over a span of dates. */
    @PostMapping("/{personId}/holds")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody(required = false) HoldForm form) {
        return PersonView.of(service.hold(sheetId, SignedIn.name(user), personId,
                form == null ? null : form.from(), form == null ? null : form.to()));
    }

    /** The person named as a patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** Name another person on the sheet as a patron of this one. */
    @PostMapping("/{personId}/patrons")
    public PersonView addPatron(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PatronForm form) {
        return PersonView.of(service.addPatron(sheetId, SignedIn.name(user), personId, form.patronId()));
    }

    /** Someone else saved the person between the version check and the write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public void savedMeanwhile() {
    }

    /** Another user took the name between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
