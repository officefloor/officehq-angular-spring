package net.officefloor.hq.app.person;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementSummary;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /** A grade a person was given and the date it applies from (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
        public static GradeView of(PersonGrade grade) {
            return new GradeView(grade.getGrade(), grade.getFrom());
        }
    }

    /** A hold put on a person: from its from-date to its to-date (null: it does not end). */
    public record HoldView(LocalDate from, LocalDate to) {
        public static HoldView of(PersonHold hold) {
            return new HoldView(hold.getFrom(), hold.getTo());
        }
    }

    /** A past retirement of a person: from the date they were retired from to the day they were unretired. */
    public record RetirementView(LocalDate from, LocalDate to) {
        public static RetirementView of(PersonRetirement retirement) {
            return new RetirementView(retirement.getFrom(), retirement.getTo());
        }
    }

    /** A SNAPSHOT written for a person: the day being left and their due-total then ("n/a" for none). */
    public record SnapshotView(LocalDate day, String dueTotal) {
        public static SnapshotView of(PersonSnapshot snapshot) {
            return new SnapshotView(snapshot.getDay(), snapshot.getDueTotal());
        }
    }

    /** A person as the screen shows them, with the grade in force on the sheet's today and every
     *  grade they were given from the earliest, how many statements were taken for them and the
     *  last one's due-total and date (null when none was), and whether they are retired and from
     *  when (null when not), and the holds put on them in the order they were, and the ids of their patrons in the order
     *  they were named, and whether the signed-in user watches them, and their own terms in force on the sheet's today (null for none), and their contact (null for none), and their aliases sorted as names are compared, and the sheet they were transferred from (null for none), and their past retirements in the order they ended, and their tags sorted (empty for none), and the snapshots written for them oldest first, and their statements' lines oldest first. */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            int statementCount, String statedTotal, LocalDate statementDate, boolean retired, LocalDate retiredFrom,
            List<HoldView> holds, List<Long> patronIds, boolean watched, Integer terms, String contact,
            List<String> aliases, boolean clashing, String origin, List<RetirementView> retirements,
            List<String> tags, List<SnapshotView> snapshots, List<StatementSummary.Line> statements) {
        public static PersonView of(Person person, List<PersonGrade> grades, LocalDate today,
                StatementSummary statements, List<PersonHold> holds, List<Long> patronIds, boolean watched,
                List<String> aliases, boolean clashing, List<PersonRetirement> retirements,
                List<PersonSnapshot> snapshots, Integer terms) {
            return new PersonView(person.getId(), person.getName(), PersonGradeService.gradeOn(grades, today),
                    grades.stream().map(GradeView::of).toList(), person.getVersion(), statements.count(),
                    statements.lastDueTotal(), statements.lastDate(), person.isRetired(), person.getRetiredFrom(),
                    holds.stream().map(HoldView::of).toList(), patronIds, watched, terms,
                    person.getContact(), aliases, clashing, person.getOriginSheetId(),
                    retirements.stream().map(RetirementView::of).toList(), person.getTags(),
                    snapshots.stream().map(SnapshotView::of).toList(), statements.lines());
        }
    }

    /** A person's fields: a name that is not blank, a grade of A, B or C, and their own terms in whole
     *  days (null for none) recorded from their from-date (blank: from the beginning of time). */
    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @Min(0) @Max(36500) Integer terms,
            LocalDate termsFrom,
            @Size(max = 200) String contact,
            @Size(max = 2000) String tags) {
    }

    /** A person's fields as saved from an editor: the grade applies from its from-date (blank takes
     *  the sheet's today), their own terms in whole days (null for none) recorded from their from-date (blank:
     *  from the beginning of time), with the version of the person the editor loaded. */
    public record PersonUpdate(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Min(0) @Max(36500) Integer terms,
            LocalDate termsFrom,
            @Size(max = 200) String contact,
            @Size(max = 2000) String tags,
            @NotNull Long version) {
    }

    /** People selected to be given a grade together: a grade of A, B or C and the date it applies from
     *  (blank takes the sheet's today). */
    public record BulkGradeRequest(
            @NotNull @Size(max = 10000) Set<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** People selected to be retired together from a date (blank takes the sheet's today). */
    public record BulkRetireRequest(
            @NotNull @Size(max = 10000) Set<@NotNull Long> personIds,
            LocalDate from) {
    }

    /** A retirement: the date the person is retired from (blank takes the sheet's today). */
    public record RetireRequest(LocalDate from) {
    }

    /** A hold: the date it runs from (blank takes the sheet's today) and an optional to-date. */
    public record HoldRequest(LocalDate from, LocalDate to) {
    }

    /** An alias to give a person. */
    public record AliasRequest(@Size(max = 200) String alias) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchRequest(@NotNull Boolean watched) {
    }

    /** A move up or down the list, and whether the list shows retired people (they are passed over when not). */
    public record MoveRequest(@NotNull @Pattern(regexp = "up|down") String direction, boolean includeRetired) {
    }

    /** A patron to name: the id of another person on the sheet. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    /** Pasted lines of name,grade,terms,contact, one person per line. */
    public record ImportRequest(@NotNull @Size(max = 1_000_000) String text) {
    }

    /** The people an import added and those it updated, each in the order of their lines. */
    public record ImportView(List<PersonView> added, List<PersonView> updated) {
    }

    /** A merge: the id of the other person on the sheet to merge into. */
    public record MergeRequest(@NotNull Long intoId) {
    }

    /** A transfer: the id of the sheet to transfer the person to. */
    public record TransferRequest(@Size(max = 200) String toSheetId) {
    }

    private final PersonService service;
    private final StatementService statements;
    private final RollupService rollups;
    private final PersonGradeService grades;
    private final SheetService sheets;
    private final BulkGradeService bulkGrade;
    private final BulkRetireService bulkRetire;
    private final PersonMergeService merges;
    private final PersonTransferService transfers;
    private final PersonImportService imports;

    public PersonController(PersonService service, StatementService statements, RollupService rollups,
            PersonGradeService grades, SheetService sheets, BulkGradeService bulkGrade, PersonMergeService merges,
            PersonTransferService transfers, PersonImportService imports, BulkRetireService bulkRetire) {
        this.bulkRetire = bulkRetire;
        this.imports = imports;
        this.transfers = transfers;
        this.bulkGrade = bulkGrade;
        this.merges = merges;
        this.service = service;
        this.statements = statements;
        this.rollups = rollups;
        this.grades = grades;
        this.sheets = sheets;
    }

    /** The sheet's people in list order: as they stand now, or as they stood at the
     *  end of the screen's as-of date (those added by then, the date standing in for the sheet's today). */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = SignedInUser.HEADER, required = false) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        String sheet = sheetId.trim();
        LocalDate asOf = AsOf.parse(asOfHeader);
        Set<Long> watched = user == null ? Set.of() : service.watchedIds(sheet, SignedInUser.decode(user), asOf);
        Map<Long, StatementSummary> summaries = statements.summaries(sheet, asOf);
        Map<Long, List<Long>> patrons = service.patronIds(sheet, asOf);
        Map<Long, List<PersonGrade>> given = grades.bySheet(sheet, asOf);
        Map<Long, List<PersonHold>> holds = service.holds(sheet, asOf);
        Map<Long, List<String>> aliases = service.aliases(sheet, asOf);
        Map<Long, List<PersonRetirement>> retirements = service.retirements(sheet, asOf);
        Map<Long, List<PersonSnapshot>> snapshots = service.snapshots(sheet, asOf);
        LocalDate today = asOf != null ? asOf : sheets.today(sheet);
        Map<Long, List<PersonTerms>> dated = service.datedTerms(sheet);
        List<Person> them = service.list(sheet, asOf);
        Set<Long> clashing = service.clashingIds(sheet, them, aliases);
        return them.stream()
                .map(p -> PersonView.of(p, given.getOrDefault(p.getId(), List.of()), today,
                        summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        holds.getOrDefault(p.getId(), List.of()),
                        patrons.getOrDefault(p.getId(), List.of()), watched.contains(p.getId()),
                        aliases.getOrDefault(p.getId(), List.of()), clashing.contains(p.getId()),
                        retirements.getOrDefault(p.getId(), List.of()),
                        snapshots.getOrDefault(p.getId(), List.of()),
                        PersonTermsService.termsOn(p, dated.getOrDefault(p.getId(), List.of()), today)))
                .toList();
    }

    /** Add a person to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PersonRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by, () -> service.add(sheet, by, request.name(),
                request.grade(), request.terms(), request.termsFrom(), request.contact(), request.tags())), by);
    }

    /** Change a person's name and give them a grade from a date; refused when the person was saved since the editor loaded them. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PersonUpdate request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by, () -> service.update(sheet, by, id, request.version(),
                request.name(), request.grade(), request.gradeFrom(), request.terms(), request.termsFrom(), request.contact(),
                request.tags())), by);
    }

    /** Add or update a person per pasted line, in order, each as the form would; none when any line is refused. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportView importPeople(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated ImportRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        PersonImportService.Result result = imports.importPeople(sheet, by, request.text());
        return new ImportView(result.added().stream().map(person -> withStatements(person, by)).toList(),
                result.updated().stream().map(person -> withStatements(person, by)).toList());
    }

    /** Give the selected people a grade from a date, in list order, each as its editor would; the
     *  people given it, in that order. */
    @PostMapping("/grade")
    public List<PersonView> grade(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated BulkGradeRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return bulkGrade.give(sheet, by, request.personIds(), request.grade(), request.from()).stream()
                .map(person -> withStatements(person, by)).toList();
    }

    /** Retire the selected people from a date, in list order, each as its row's retire would; the
     *  people retired, in that order. */
    @PostMapping("/retire")
    public List<PersonView> retireAll(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated BulkRetireRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return bulkRetire.retire(sheet, by, request.personIds(), request.from()).stream()
                .map(person -> withStatements(person, by)).toList();
    }

    /** Retire a person from a date. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody RetireRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by, () -> service.retire(sheet, by, id, request.from())), by);
    }

    /** Unretire a retired person on the sheet's today. */
    @PostMapping("/{id}/unretire")
    public PersonView unretire(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by, () -> service.unretire(sheet, by, id)), by);
    }

    /** Put a hold on a person from a date to an optional to-date. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody HoldRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by,
                () -> service.hold(sheet, by, id, request.from(), request.to())), by);
    }

    /** Name another person on the sheet as a person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PatronRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(service.addPatron(sheet, by, id, request.patronId()), by);
    }

    /** Merge a person into another on the sheet: the merged person leaves the sheet; the person merged
     *  into, as they stand after it. */
    @PostMapping("/{id}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated MergeRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(rollups.track(sheet, by, () -> merges.merge(sheet, by, id, request.intoId())), by);
    }

    /** Transfer a person to another sheet by id: they leave this sheet. */
    @PostMapping("/{id}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated TransferRequest request) {
        transfers.transfer(sheetId.trim(), SignedInUser.decode(user), id, request.toSheetId());
    }

    /** Give a person an alias. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated AliasRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(service.addAlias(sheet, by, id, request.alias()), by);
    }

    /** Start or stop the signed-in user watching a person. */
    @PostMapping("/{id}/watch")
    public PersonView watch(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated WatchRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(service.watch(sheet, by, id, request.watched()), by);
    }

    /** Move a person up or down the list, swapping them with their neighbour there. */
    @PostMapping("/{id}/move")
    public PersonView move(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated MoveRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return withStatements(service.move(sheet, by, id, request.direction().equals("up"), request.includeRetired()), by);
    }

    /** A date that is not a real yyyy-mm-dd date, or a hold that ends before it starts, is refused. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HoldRefusedException.class,
            AliasRefusedException.class, ImportRefusedException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }

    /** Someone else saved the person since the editor loaded them: the person as stored now. */
    @ExceptionHandler(StalePersonException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public PersonView stale(StalePersonException e, HttpServletRequest request) {
        String user = request.getHeader(SignedInUser.HEADER);
        return withStatements(e.getCurrent(), user == null ? null : SignedInUser.decode(user));
    }

    private PersonView withStatements(Person person, String user) {
        return PersonView.of(person, grades.of(person.getId()), sheets.today(person.getSheetId()),
                statements.summary(person.getSheetId(), person.getId()), service.holds(person.getId()),
                service.patronIds(person.getId()), user != null && service.watches(person.getId(), user),
                service.aliases(person.getId()), service.clashing(person), service.retirements(person.getId()),
                service.snapshots(person.getId()), service.termsOn(person, sheets.today(person.getSheetId())));
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

    /** The name or contact is already in use on the sheet, the patron may not be named, or the merge may
     *  not be made, or the transfer may not be made; a concurrent
     *  save is caught by the unique index. */
    @ExceptionHandler({NameInUseException.class, ContactInUseException.class, PatronRefusedException.class,
            MergeRefusedException.class, TransferRefusedException.class, MoveRefusedException.class,
            UnretireRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
