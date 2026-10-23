package net.officefloor.hq.app.person;

import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.AsOfGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /**
     * What the screen is told about a person, with their statement count, and the last statement's
     * due-total (null when n/a or none taken) and date (null when none taken), and the date they are
     * retired from (null when not retired), whether they have any hold, their holds, and the ids of their patrons
     * in the order they were named, and whether their name or an alias is in use on another sheet of the
     * sheet's tree (clashing), and the sheet they were last transferred from (null when never), and their
     * flag's reason and last day (both null when never flagged).
     * Their grades are listed by the date each is in force from, the first with none (from the
     * beginning of time). Their terms are their own from the beginning of time; the own terms recorded
     * since are listed by the date each is in force from.
     */
    public record PersonView(long id, String name, List<GradeView> grades, Integer terms, List<TermsView> termsFrom,
            String contact, List<String> tags,
            List<String> tagsUsed, long version, int statementCount,
            String statementTotal, LocalDate statementDate, LocalDate retiredFrom, List<RetirementView> retirements, boolean held,
            List<HoldView> holds, List<Long> patronIds, List<String> aliases, boolean clashing,
            String originSheetId, List<SnapshotView> snapshots, List<StatementLineView> statementLines,
            String flagReason, LocalDate flagUntil) {

        public static PersonView of(Person person) {
            return of(person, false);
        }

        public static PersonView of(Person person, boolean clashing) {
            List<GradeView> grades = new ArrayList<>();
            grades.add(new GradeView(person.getGrade(), null));
            person.getGradesFrom().forEach((from, grade) -> grades.add(new GradeView(grade, from)));
            List<TermsView> termsFrom = new ArrayList<>();
            person.getTermsFrom().forEach((from, terms) -> termsFrom.add(new TermsView(terms, from)));
            return new PersonView(person.getId(), person.getName(), grades, person.getTerms(), termsFrom,
                    person.getContact(),
                    person.getTags(), person.getTagsUsed(), person.getVersion(),
                    person.getStatementCount(),
                    person.getStatementTotal() == null ? null : person.getStatementTotal().toPlainString(),
                    person.getStatementDate(), person.getRetiredFrom(),
                    person.getRetirements().stream().map(r -> new RetirementView(r.getFromDate(), r.getToDate())).toList(),
                    person.isHeld(),
                    person.getHolds().stream().map(hold -> new HoldView(hold.getFromDate(), hold.getToDate())).toList(),
                    person.getPatronIds(), person.getAliases(), clashing, person.getOriginSheetId(),
                    person.getSnapshots().stream()
                            .map(snapshot -> new SnapshotView(snapshot.getAsOf(), Rollups.shown(snapshot.getDueTotal())))
                            .toList(),
                    person.getStatements().stream()
                            .map(statement -> new StatementLineView(statement.getTakenOn(),
                                    Rollups.shown(statement.getTotal())))
                            .toList(),
                    person.getFlagReason(), person.getFlagUntil());
        }
    }

    /** A past retirement of a person, from its date to the day they were unretired. */
    public record RetirementView(LocalDate from, LocalDate to) {
    }

    /** A snapshot line of a person: the day being left and their due-total on it (n/a when no rate applied). */
    public record SnapshotView(LocalDate asOf, String dueTotal) {
    }

    /** A statement line of a person: the date it was taken as of and its due-total (n/a when no rate applied). */
    public record StatementLineView(LocalDate date, String dueTotal) {
    }

    /** A hold on a person, from a date to a date (null: no end). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /** A grade a person has, from a date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
    }

    /** Own terms a person has from a date, in days (null: none of their own). */
    public record TermsView(Integer terms, LocalDate from) {
    }

    /**
     * A person's values as entered on the form: a name is required, the grade is A, B or C. On an edit
     * the grade is recorded from its date (none: the sheet's today); a person's first grade applies
     * from the beginning of time. Their own terms are a whole number of days, none for no terms of
     * their own; on an edit they are recorded from their date (none: from the beginning of time). A contact is kept trimmed and lower-cased, blank for none. Tags are comma separated
     * (blank: none; none at all keep the person's own). An edit says whether the
     * grade was chosen on the form (it is then recorded from its date even when already in force there), and whether the
     * terms and the contact were typed on the form (PERSON_EDITED then carries them), and carries the version of the person the form loaded.
     */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @PositiveOrZero @Max(36500) Integer terms,
            LocalDate termsFrom,
            @Size(max = 200) String contact,
            boolean termsGiven,
            boolean contactGiven,
            Long version,
            @Size(max = 1000) String tags,
            boolean gradeGiven) {
    }

    /** The people selected to be given a grade together, the grade, and the date it is in force from (none: the sheet's today). */
    public record BulkGradeForm(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** A grade chosen on a person's row, from the version of the person the row shows. */
    public record RowGradeForm(@NotNull @Pattern(regexp = "[ABC]") String grade, @NotNull Long version) {
    }

    /** The people selected to be retired together, and the date they are retired from (none: the sheet's today). */
    public record BulkRetireForm(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull Long> personIds,
            LocalDate from) {
    }

    /** The import box's text: lines of name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    private final PersonService service;
    private final ImportService imports;
    private final BulkGradeService bulkGrade;
    private final BulkRetireService bulkRetire;
    private final WatchService watches;
    private final MergeService merges;
    private final TransferService transfers;
    private final SheetHistory history;

    public PersonController(PersonService service, ImportService imports, BulkGradeService bulkGrade,
            BulkRetireService bulkRetire, WatchService watches,
            MergeService merges, TransferService transfers, SheetHistory history) {
        this.service = service;
        this.imports = imports;
        this.bulkGrade = bulkGrade;
        this.bulkRetire = bulkRetire;
        this.watches = watches;
        this.merges = merges;
        this.transfers = transfers;
        this.history = history;
    }

    /** The sheet's people in list order; with an as-of date, as they stood at the end of it. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        return history.views(sheetId, AsOfGuard.date(asOf)).map(SheetHistory.Views::people)
                .orElseGet(() -> {
                    Set<Long> clashing = service.clashing(sheetId);
                    return service.list(sheetId).stream()
                            .map(person -> PersonView.of(person, clashing.contains(person.getId()))).toList();
                });
    }

    /** Add a person to the sheet. */
    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PersonForm form) {
        return view(service.add(sheetId, SignedIn.name(user), form.name(), form.grade(), form.terms(),
                form.contact(), form.tags()));
    }

    /** The people an import added and those it updated, each in the order of their lines. */
    public record ImportView(List<PersonView> added, List<PersonView> updated) {
    }

    /**
     * Add a person per imported line, in order, each as the person form would, or update the person the
     * line names; nothing is imported when any line is refused.
     */
    @PostMapping("/import")
    public ImportView importPeople(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ImportForm form) {
        ImportService.Imported imported = imports.importPeople(sheetId, SignedIn.name(user), form.text());
        return new ImportView(imported.added().stream().map(this::view).toList(),
                imported.updated().stream().map(this::view).toList());
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
        return view(service.edit(sheetId, SignedIn.name(user), personId, form.version(), form.name(),
                form.grade(), form.gradeFrom(), form.terms(), form.termsFrom(), form.contact(), form.termsGiven(),
                form.contactGiven(), form.tags(), form.gradeGiven()));
    }

    /** Give the selected people a grade from a date together, each as the person form would: the people changed. */
    @PostMapping("/grade")
    public List<PersonView> grade(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkGradeForm form) {
        return bulkGrade.grade(sheetId, SignedIn.name(user), form.personIds(), form.grade(), form.from()).stream()
                .map(PersonView::of).toList();
    }

    /** Record the grade chosen on the person's row from the sheet's today, as the person form would. */
    @PostMapping("/{personId}/grade")
    public PersonView gradeRow(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RowGradeForm form) {
        return view(service.grade(sheetId, SignedIn.name(user), personId, form.version(), form.grade()));
    }

    /** Retire the selected people from a date together, each as the row's retire would: the people retired. */
    @PostMapping("/retire")
    public List<PersonView> retireAll(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid BulkRetireForm form) {
        return bulkRetire.retire(sheetId, SignedIn.name(user), form.personIds(), form.from()).stream()
                .map(PersonView::of).toList();
    }

    /** Swap a person with the one above them in the list (up) or below them (down). */
    @PostMapping("/{personId}/move/{direction}")
    public PersonView move(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @PathVariable @Pattern(regexp = "up|down") String direction,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return view(service.move(sheetId, SignedIn.name(user), personId, "up".equals(direction)));
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
        return view(service.retire(sheetId, SignedIn.name(user), personId,
                form == null ? null : form.from()));
    }

    /** Unretire a retired person on the sheet's today. */
    @PostMapping("/{personId}/unretire")
    public PersonView unretire(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return view(service.unretire(sheetId, SignedIn.name(user), personId));
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
        return view(service.hold(sheetId, SignedIn.name(user), personId,
                form == null ? null : form.from(), form == null ? null : form.to()));
    }

    /** A flag's reason and the last day it is in force. */
    public record FlagForm(@Size(max = 200) String reason, @NotNull LocalDate until) {
    }

    /** Flag a person with a reason until a date; a steward only. */
    @PostMapping("/{personId}/flag")
    public PersonView flag(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FlagForm form) {
        return view(service.flag(sheetId, SignedIn.name(user), personId, form.reason(), form.until()));
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
        return view(service.addPatron(sheetId, SignedIn.name(user), personId, form.patronId()));
    }

    /** An alias a person goes by. */
    public record AliasForm(@Size(max = 200) String alias) {
    }

    /** Give a person an alias. */
    @PostMapping("/{personId}/aliases")
    public PersonView addAlias(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid AliasForm form) {
        return view(service.addAlias(sheetId, SignedIn.name(user), personId, form.alias()));
    }

    /** The person another is merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** Merge a person into another on the sheet; a steward only. The person merged into, after it. */
    @PostMapping("/{personId}/merge")
    public PersonView merge(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid MergeForm form) {
        return view(merges.merge(sheetId, SignedIn.name(user), personId, form.intoId()));
    }

    /** The sheet a person is transferred to. */
    public record TransferForm(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String toSheetId) {
    }

    /** Transfer a person to another sheet by its id; a steward only. They leave this sheet. */
    @PostMapping("/{personId}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TransferForm form) {
        transfers.transfer(sheetId, SignedIn.name(user), personId, form.toSheetId());
    }

    /**
     * Undo the transfer that brought a person to this sheet on its today; a steward only. They go back
     * to the sheet they came from.
     */
    @PostMapping("/{personId}/transfer-undo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transferUndo(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        transfers.undo(sheetId, SignedIn.name(user), personId);
    }

    /**
     * The ids of the people on the sheet the signed-in user watches, as they stand now (also while the
     * sheet is shown as of a date, as watches are not kept in the sheet's history).
     */
    @GetMapping("/watched")
    public List<Long> watched(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return watches.watched(sheetId, SignedIn.name(user));
    }

    /** Start watching a person as the signed-in user. */
    @PutMapping("/{personId}/watch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void watch(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        watches.watch(sheetId, SignedIn.name(user), personId);
    }

    /** Stop watching a person as the signed-in user. */
    @DeleteMapping("/{personId}/watch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unwatch(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        watches.unwatch(sheetId, SignedIn.name(user), personId);
    }

    /** What the screen is told about a person, with whether they clash. */
    private PersonView view(Person person) {
        return PersonView.of(person, service.clashing(person));
    }

    /** Someone else saved the person between the version check and the write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public void savedMeanwhile() {
    }

    /** Another user took the name or contact between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
