package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementSummary;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The people on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /** One of a person's holds, from its from-date to its to-date (null: it does not end). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /** One of a person's grades, from its from-date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
    }

    /**
     * A person as shown, with their grade in force on the sheet's today and every grade with its from-date, with how many statements they have had and the due-total and date of the last one. */
    public record PersonView(Long id, String name, String grade, Integer terms, String contact, long version, int statementCount,
            String statedTotal, LocalDate statementDate, boolean retired, LocalDate retiredFrom, boolean held,
            List<Long> patronIds, List<GradeView> grades, List<HoldView> holds, boolean watched, List<String> aliases) {

        static PersonView of(Person person, LocalDate today, StatementSummary statements, List<Long> patronIds,
                boolean watched, List<String> aliases) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today), person.getTerms(),
                    person.getContact(), person.getVersion(),
                    statements.count(), statements.lastDueTotal(), statements.lastDate(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(), patronIds,
                    person.getGrades().stream().map(g -> new GradeView(g.getGrade(), g.getFromDate())).toList(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFromDate(), h.getToDate())).toList(),
                    watched, aliases);
        }
    }

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String terms,
            @Size(max = 200) String contact,
            Long version) {

        /** The person's own terms in whole days; null when blank. */
        Integer termsValue() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }
    }

    /** The people to give a grade together, the grade, and the date it applies from (null: the sheet's today). */
    public record BulkGradeRequest(
            @NotNull @Size(max = 10000) List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** The date to retire from; null for the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    /** The dates a hold runs from (null: the sheet's today) and to (null: it does not end). */
    public record HoldRequest(LocalDate from, LocalDate to) {
    }

    /** The date to take a statement as of; null for the sheet's today. */
    public record StatementRequest(LocalDate asOf) {
    }

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    /** Another name for the person. */
    public record AliasRequest(@Size(max = 200) String alias) {
    }

    /** The person to merge into. */
    public record MergeRequest(@NotNull Long intoId) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchRequest(@NotNull Boolean on) {
    }

    private final PersonService service;
    private final StatementService statements;
    private final SheetService sheets;
    private final WatchService watches;
    private final BulkGradeService bulkGrade;
    private final MergeService merges;

    public PersonController(PersonService service, StatementService statements, SheetService sheets,
            WatchService watches, BulkGradeService bulkGrade, MergeService merges) {
        this.service = service;
        this.merges = merges;
        this.bulkGrade = bulkGrade;
        this.watches = watches;
        this.statements = statements;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, StatementSummary> summaries = statements.summaries(sheetId.trim());
        Map<Long, List<Long>> patronIds = service.patronIds(sheetId.trim());
        Map<Long, List<String>> aliases = service.aliases(sheetId.trim());
        Set<Long> watched = watches.watchedBy(sheetId.trim(), user.trim());
        List<Person> people = service.list(sheetId.trim());
        LocalDate today = people.isEmpty() ? null : sheets.today(sheetId.trim());
        return people.stream()
                .map(p -> PersonView.of(p, today, summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        patronIds.getOrDefault(p.getId(), List.of()), watched.contains(p.getId()),
                        aliases.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.add(sheetId.trim(), user.trim(), request.name(), request.grade(),
                request.termsValue(), request.contact()), user.trim());
    }

    /** Saves the person; with a version, only when it is still the version stored. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.update(sheetId.trim(), user.trim(), id, request.version(), request.name(),
                request.grade(), request.gradeFrom(), request.termsValue(), request.contact()), user.trim());
    }

    /** Give the selected people a grade from a date, in list order, each as the person form would. */
    @PostMapping("/bulk-grade")
    public BulkGradeService.Result bulkGrade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkGradeRequest request) {
        return bulkGrade.grade(sheetId.trim(), user.trim(), request.ids(), request.grade(), request.from());
    }

    /**
     * Take a statement of the person, on the sheet's today or as of an earlier date: record their due-total and bind
     * their counting claims to their dues.
     */
    @PostMapping("/{id}/statement")
    public PersonView statement(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) StatementRequest request) {
        return view(statements.take(sheetId.trim(), user.trim(), id, request == null ? null : request.asOf()),
                user.trim());
    }

    /** Cancel the person's last statement (a steward only): the claims it bound are live again. */
    @DeleteMapping("/{id}/statement")
    public PersonView cancelStatement(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(statements.cancelLast(sheetId.trim(), user.trim(), id), user.trim());
    }

    /** Retire the person from a date. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(service.retire(sheetId.trim(), user.trim(), id, request.from()), user.trim());
    }

    /** Add a hold of the person over dates. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldRequest request) {
        return view(service.hold(sheetId.trim(), user.trim(), id, request.from(), request.to()), user.trim());
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId.trim(), user.trim(), id, request.patronId()), user.trim());
    }

    /** Give the person another name the sheet knows them by. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AliasRequest request) {
        return view(service.addAlias(sheetId.trim(), user.trim(), id, request.alias()), user.trim());
    }

    /** Merge the person into another on the sheet (a steward only): the person is removed; the other is returned. */
    @PostMapping("/{id}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MergeRequest request) {
        return view(merges.merge(sheetId.trim(), user.trim(), id, request.intoId()), user.trim());
    }

    /** Start or stop watching the person as the signed-in user. */
    @PutMapping("/{id}/watch")
    public PersonView watch(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid WatchRequest request) {
        return view(watches.watch(sheetId.trim(), user.trim(), id, request.on()), user.trim());
    }

    private PersonView view(Person person, String user) {
        return PersonView.of(person, sheets.today(person.getSheetId()), statements.summary(person.getSheetId(), person.getId()),
                service.patronIdsOf(person.getId()), watches.watches(person.getId(), user),
                service.aliasesOf(person.getId()));
    }
}
