package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** A hold a person was put on, from {@code from} to {@code to} (both included; null: no end). */
    public record HoldView(LocalDate from, LocalDate to) {
        public static HoldView of(PersonHold hold) {
            return new HoldView(hold.getFrom(), hold.getTo());
        }
    }

    /** A person: the grade in force on the sheet's {@code today}, and every grade they were given by
     * from-date; {@code retiredFrom} is null while they are not retired; {@code held} is whether they
     * have any hold, listed in {@code holds} by from-date; {@code clashing} is whether their name or an
     * alias of theirs is used on another sheet of the sheet's tree. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, Integer terms,
            String contact, List<String> aliases, boolean clashing) {
        public static PersonView of(Person person, LocalDate today, boolean clashing) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(GradeView::of).toList(), person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(HoldView::of).toList(), person.getTerms(),
                    person.getContact(), person.getAliases(), clashing);
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

    /** The person another is merged into. */
    public record MergeRequest(@NotNull Long intoId) {
    }

    /** The id of the sheet a person is transferred to. */
    public record TransferRequest(@NotBlank @Size(max = 100) String toSheet) {
    }

    /** The date a person is retired from (the sheet's today when left out). */
    public record RetireRequest(LocalDate from) {
    }

    /** A hold to put a person on: from {@code from} (the sheet's today when left out) to {@code to}
     * (no end when left out). */
    public record HoldRequest(LocalDate from, LocalDate to) {
    }

    /** A person's name (not blank; kept without the spaces around it and unique on the sheet
     * without regard to case) and grade, one of A, B or C, given from {@code gradeFrom} on a save (the
     * sheet's today when left out; a person's first grade applies from the beginning of time), and
     * their own terms, a whole number of days, 0 or more (blank or null for none), and their contact
     * (kept without the spaces around it, in lower case, and unique on the sheet; blank or null for
     * none). */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\s*(\\d{1,4})?\\s*") String terms,
            Long version,
            @Size(max = 200) String contact) {
        Integer termsValue() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }
    }

    /** An alias to give a person (blank is refused). */
    public record AliasRequest(@Size(max = 200) String alias) {
    }

    /** A grade to give the people {@code personIds} together, from {@code from} (the sheet's today when
     * left out). */
    public record BulkGradeRequest(@NotNull @Size(max = 1000) List<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** How a bulk grade went: how many people it changed, and those people. */
    public record BulkGradeView(int graded, List<PersonView> people) {
    }

    /** The pasted lines of {@code name,grade,terms,contact}. */
    public record ImportRequest(@NotNull @Size(max = 200_000) String text) {
    }

    /** How an import went: how many people it added, and those people, in the order of their lines. */
    public record ImportView(int added, List<PersonView> people) {
    }

    /** A save refused because the person was saved by someone else since the editor loaded them:
     * the person as now stored. */
    public record StaleView(String error, PersonView current) {
    }

    /** A save refused for a reason the form tells apart: {@code contact-taken}. */
    public record RefusedView(String error) {
    }

    private final PersonService service;

    private final PersonBulkService bulk;

    private final SheetHistory history;

    private final PersonImportService imports;

    private final PersonMergeService merges;

    private final PersonTransferService transfers;

    public PersonController(PersonService service, PersonBulkService bulk, SheetHistory history,
            PersonMergeService merges, PersonTransferService transfers, PersonImportService imports) {
        this.service = service;
        this.imports = imports;
        this.transfers = transfers;
        this.bulk = bulk;
        this.merges = merges;
        this.history = history;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.read(sheetId.trim(), asOf).people().stream().map(person -> PersonView.of(person, asOf, service.clashes(person)))
                    .toList();
        }
        LocalDate today = service.today(sheetId.trim());
        return service.list(sheetId.trim()).stream().map(person -> PersonView.of(person, today, service.clashes(person))).toList();
    }

    @GetMapping("/patrons")
    public List<PatronView> patrons(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<PersonPatron> patrons = asOf == null ? service.patrons(sheetId.trim())
                : history.read(sheetId.trim(), asOf).patrons();
        return patrons.stream().map(PatronView::of).toList();
    }

    @PostMapping("/{personId}/patrons")
    @ResponseStatus(HttpStatus.CREATED)
    public PatronView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return PatronView.of(service.addPatron(sheetId.trim(), user.trim(), personId, request.patronId()));
    }

    /** The ids of the people the signed-in user watches. */
    @GetMapping("/watched")
    public List<Long> watched(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) return history.read(sheetId.trim(), asOf).watchedBy(user.trim());
        return service.watched(sheetId.trim(), user.trim());
    }

    @PostMapping("/{personId}/watch")
    public PersonView watch(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId, service.watch(sheetId.trim(), user.trim(), personId, true));
    }

    @DeleteMapping("/{personId}/watch")
    public PersonView unwatch(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId, service.watch(sheetId.trim(), user.trim(), personId, false));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(sheetId, service.add(sheetId.trim(), user.trim(), request.name(), request.grade(),
                request.termsValue(), request.contact()));
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
                    request.name(), request.grade(), request.gradeFrom(), request.termsValue(), request.contact()));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another save of the person committed while this one was under way.
            throw new PersonStaleException(service.get(sheetId.trim(), personId));
        }
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportView importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ImportRequest request) {
        List<Person> added = imports.importPeople(sheetId.trim(), user.trim(), request.text());
        return new ImportView(added.size(), added.stream().map(p -> view(sheetId, p)).toList());
    }

    @PostMapping("/bulk-grade")
    public BulkGradeView bulkGrade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkGradeRequest request) {
        List<Person> changed = bulk.grade(sheetId.trim(), user.trim(), request.personIds(), request.grade(),
                request.from());
        return new BulkGradeView(changed.size(), changed.stream().map(p -> view(sheetId, p)).toList());
    }

    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) RetireRequest request) {
        return view(sheetId, service.retire(sheetId.trim(), user.trim(), personId,
                request == null ? null : request.from()));
    }

    /** Merges the person into another; answers the person merged into. */
    @PostMapping("/{personId}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MergeRequest request) {
        return view(sheetId, merges.merge(sheetId.trim(), user.trim(), personId, request.intoId()));
    }

    /** Transfers the person to another sheet; answers them as they now are there. */
    @PostMapping("/{personId}/transfer")
    public PersonView transfer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TransferRequest request) {
        String to = request.toSheet().trim();
        return view(to, transfers.transfer(sheetId.trim(), user.trim(), personId, to));
    }

    @PostMapping("/{personId}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AliasRequest request) {
        return view(sheetId, service.addAlias(sheetId.trim(), user.trim(), personId, request.alias()));
    }

    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) HoldRequest request) {
        return view(sheetId, service.hold(sheetId.trim(), user.trim(), personId,
                request == null ? null : request.from(), request == null ? null : request.to()));
    }

    @ExceptionHandler(PersonContactTakenException.class)
    public ResponseEntity<RefusedView> contactTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new RefusedView("contact-taken"));
    }

    @ExceptionHandler(PersonStaleException.class)
    public ResponseEntity<StaleView> stale(PersonStaleException e) {
        Person current = e.getCurrent();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new StaleView("stale", PersonView.of(current, service.today(current.getSheetId()),
                        service.clashes(current))));
    }

    /** The person as the sheet shows them on its today. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId.trim()), service.clashes(person));
    }
}
