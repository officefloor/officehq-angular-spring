package net.officefloor.hq.app.person;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.history.AsOf;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The people on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /** A person as the screen shows it, with how many statements were taken of them and the last one's
     *  due-total (n/a when it was) and date; none of either before their first statement; and the ids
     *  of their patrons. Their grade is the one in force on the sheet's today; grades lists every grade
     *  they were given, each with its from-date (YYYY-MM-DD, none for the first); watched says whether
     *  the signed-in user watches them. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, long version, int statementCount,
            String statedTotal, String statementDate, boolean retired, String retiredFrom,
            boolean held, List<HoldView> holds, List<Long> patronIds, boolean watched, Integer terms) {

        public static PersonView of(Person person, LocalDate today, List<Statement> statements, List<Long> patronIds,
                boolean watched) {
            Statement last = statements.isEmpty() ? null : statements.get(statements.size() - 1);
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(GradeView::of).toList(), person.getVersion(),
                    statements.size(),
                    last == null ? null : last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString(),
                    last == null ? null : last.getTakenOn().toString(),
                    person.isRetired(), person.getRetiredFrom() == null ? null : person.getRetiredFrom().toString(),
                    person.isHeld(), person.getHolds().stream().map(HoldView::of).toList(), patronIds,
                    watched, person.getTerms());
        }
    }

    /** A grade a person was given and the date it applies from; none for the beginning of time. */
    public record GradeView(String grade, String from) {

        static GradeView of(PersonGrade grade) {
            return new GradeView(grade.getGrade(), grade.getFromDate() == null ? null : grade.getFromDate().toString());
        }
    }

    /** A hold on a person: its from-date (none for the beginning of time) and its to-date (none while it
     *  has no end), each YYYY-MM-DD. */
    public record HoldView(String from, String to) {

        static HoldView of(PersonHold hold) {
            return new HoldView(hold.getFromDate() == null ? null : hold.getFromDate().toString(),
                    hold.getToDate() == null ? null : hold.getToDate().toString());
        }
    }

    /** The dates a hold covers: from a date (none takes the sheet's today) to a date (none: no end). */
    public record HoldRequest(LocalDate from, LocalDate to) {
    }

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @Min(0) Integer terms) {
    }

    /** A person's new name and grade, the date the grade applies from (none: the sheet's today, or
     *  nothing recorded when the grade is the one in force then), with the version of the person the
     *  editor loaded. */
    public record PersonUpdate(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Min(0) Integer terms,
            @NotNull Long version) {
    }

    /** The people to give a grade together, the grade, and the date it applies from (none: the sheet's
     *  today). */
    public record BulkGradeRequest(
            @NotNull @Size(min = 1, max = 10000) List<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** How many a bulk grade changed, and the people it changed. */
    public record BulkGradeView(int graded, List<PersonView> people) {
    }

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchRequest(@NotNull Boolean watched) {
    }

    /** The date a person is retired from; none takes the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    private final PersonService service;
    private final AsOf asOf;

    public PersonController(PersonService service, AsOf asOf) {
        this.service = service;
        this.asOf = asOf;
    }

    /** The sheet's people in the order they were added; as they stood at the end of a date when the screen
     *  views the sheet as of one. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String id = sheetId.trim();
        Map<Long, List<Statement>> statements = asOf == null ? service.statements(id)
                : this.asOf.statements(id, asOf).stream().collect(Collectors.groupingBy(Statement::getPersonId));
        Map<Long, List<Long>> patrons = asOf == null ? service.patrons(id)
                : this.asOf.patrons(id, asOf).stream().collect(Collectors.groupingBy(Patron::getPersonId,
                        Collectors.mapping(Patron::getPatronId, Collectors.toList())));
        List<Person> people = asOf == null ? service.list(id) : this.asOf.people(id, asOf);
        LocalDate today = asOf == null ? service.today(id) : this.asOf.today(id, asOf);
        Set<Long> watched = asOf == null ? service.watchedBy(id, user.trim())
                : this.asOf.watches(id, asOf).stream().filter(w -> w.getUser().equals(user.trim()))
                        .map(Watch::getPersonId).collect(Collectors.toSet());
        return people.stream()
                .map(p -> PersonView.of(p, today, statements.getOrDefault(p.getId(), List.of()),
                        patrons.getOrDefault(p.getId(), List.of()), watched.contains(p.getId())))
                .toList();
    }

    /** Add a person to the sheet. */
    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonRequest request) {
        Person added = service.add(sheetId.trim(), user.trim(), request.name(), request.grade(), request.terms());
        return PersonView.of(added, service.today(sheetId.trim()), List.of(), List.of(), false);
    }

    /** Change a person's name, and give them a grade from a date. */
    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonUpdate request) {
        try {
            Person updated = service.update(sheetId.trim(), user.trim(), personId, request.version(),
                    request.name(), request.grade(), request.gradeFrom(), request.terms());
            return view(updated, user.trim());
        } catch (ObjectOptimisticLockingFailureException e) {
            // saved by someone else between the version check and the write
            throw new PersonVersionConflictException(service.current(sheetId.trim(), personId));
        }
    }

    /** Give the chosen people a grade from a date together, in list order. */
    @PostMapping("/bulk-grade")
    public BulkGradeView bulkGrade(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkGradeRequest request) {
        try {
            List<Person> graded = service.bulkGrade(sheetId.trim(), user.trim(), request.personIds(), request.grade(),
                    request.from());
            return new BulkGradeView(graded.size(), graded.stream().map(p -> view(p, user.trim())).toList());
        } catch (ObjectOptimisticLockingFailureException e) {
            // one of them was saved by someone else at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone else changed one of them; try again.");
        }
    }

    /** Retire a person from a date. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody RetireRequest request) {
        Person retired = service.retire(sheetId.trim(), user.trim(), personId, request.from());
        return view(retired, user.trim());
    }

    /** Put a person on hold from a date, to a date when one is given. */
    @PostMapping("/{personId}/holds")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody HoldRequest request) {
        Person held = service.hold(sheetId.trim(), user.trim(), personId, request.from(), request.to());
        return view(held, user.trim());
    }

    /** A hold that cannot be put on is refused with the reason, for the screen to show. */
    @ExceptionHandler(HoldRefusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> holdRefused(HoldRefusedException e) {
        return Map.of("message", e.getMessage());
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{personId}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId.trim(), user.trim(), personId, request.patronId()), user.trim());
    }

    /** Start or stop watching a person as the signed-in user. */
    @PutMapping("/{personId}/watch")
    public PersonView watch(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid WatchRequest request) {
        return view(service.watch(sheetId.trim(), user.trim(), personId, request.watched()), user.trim());
    }

    /** A watch that cannot be started is refused with the reason, for the screen to show. */
    @ExceptionHandler(WatchRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> watchRefused(WatchRefusedException e) {
        return Map.of("message", e.getMessage());
    }

    /** A patron the person cannot name is refused with the reason, for the screen to show. */
    @ExceptionHandler(PatronRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> patronRefused(PatronRefusedException e) {
        return Map.of("message", e.getMessage());
    }

    private PersonView view(Person person, String user) {
        return PersonView.of(person, service.today(person.getSheetId()), service.statementsOf(person.getId()),
                service.patronsOf(person.getId()), service.watching(person.getId(), user));
    }

    /** A save from an editor that loaded an older version: refused, answering the person as it is now. */
    @ExceptionHandler(PersonVersionConflictException.class)
    public ResponseEntity<PersonView> conflict(PersonVersionConflictException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(view(e.getCurrent(), request.getHeader("X-User").trim()));
    }
}
