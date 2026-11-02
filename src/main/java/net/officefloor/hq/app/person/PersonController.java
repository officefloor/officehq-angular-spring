package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.role.ViewerAllowed;
import net.officefloor.hq.app.sheet.AsOfGuard;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

    /** What the screen shows of a person; with how many statements were taken of them, and the
     *  last one's due-total (text with two decimals, null when n/a or none) and day (null when none);
     *  whether they are retired, and from which day (null when not); and whether they have any hold, and their holds, earliest first; and their own terms in days (null: none); and their contact (null: none); and their aliases, sorted as names are compared.
     *  The grade is the one in force on the sheet's today; grades are all those recorded, each from
     *  its from-date (null: the beginning of time), earliest first. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, long version,
            int statementCount, String statedTotal, LocalDate statementDate, boolean retired, LocalDate retiredFrom,
            boolean held, List<HoldView> holds, Integer terms, String contact, List<String> aliases) {
        public static PersonView of(Person person, StatementSummary statements, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(g -> new GradeView(g.getGrade(), g.getFromDate())).toList(),
                    person.getVersion(), statements.count(),
                    statements.lastDueTotal() == null ? null : statements.lastDueTotal().setScale(2).toPlainString(),
                    statements.lastTakenOn(), person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFromDate(), h.getToDate())).toList(),
                    person.getTerms(), person.getContact(), person.getAliases());
        }
    }

    /** A grade recorded for a person, from a day (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
    }

    /** A hold on a person, from a day (null: from the beginning of time) to a day (null: open-ended). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /** The person form: a required name and a grade of A, B or C. An edit also gives the version
     *  of the person its editor loaded, and the day the grade applies from (left out, the sheet's
     *  today). Terms are the person's own, a whole number of days, 0 or more; blank for none. The
     *  contact is kept trimmed and lower-cased; blank for none. */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, Long version, LocalDate gradeFrom,
            @Pattern(regexp = "\\s*\\d{0,4}\\s*") String terms, @Size(max = 200) String contact) {
        Integer termsValue() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }

        String contactValue() {
            return contact == null || contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** The alias form: a required alias, kept trimmed. */
    public record AliasForm(@NotBlank @Size(max = 200) String alias) {
    }

    /** The merge form: the person the merged person is merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** The retire form: the day the person is retired from; left out, the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** The hold form: the day the hold starts (left out, the sheet's today) and the day it ends
     *  (left out, it is open-ended). */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** The bulk grade form: the people to give the grade, a grade of A, B or C, and the day it
     *  applies from (left out, the sheet's today). */
    public record GradeForm(@NotNull @Size(max = 1000) Set<@NotNull Long> personIds,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate from) {
    }

    /** How many people a bulk grade changed, and those people as they now show. */
    public record Graded(int graded, List<PersonView> people) {
    }

    private final PersonService service;
    private final StatementService statements;
    private final SheetService sheets;
    private final History history;
    private final PersonMergeService merges;

    public PersonController(PersonService service, StatementService statements, SheetService sheets,
            History history, PersonMergeService merges) {
        this.history = history;
        this.merges = merges;
        this.service = service;
        this.statements = statements;
        this.sheets = sheets;
    }

    /** The people on the sheet; as of a day when one is given, as they stood at the end of it (see
     *  {@link History}), that day standing in for the sheet's today. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        String sheet = sheetId.trim();
        if (asOf != null) {
            Map<Long, StatementSummary> then = statements.summaries(sheet, asOf);
            return history.asOf(sheet, asOf).people().values().stream()
                    .sorted(Comparator.comparing(Person::getId))
                    .map(p -> PersonView.of(p, then.getOrDefault(p.getId(), StatementSummary.NONE), asOf))
                    .toList();
        }
        Map<Long, StatementSummary> summaries = statements.summaries(sheet);
        LocalDate today = sheets.today(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, summaries.getOrDefault(p.getId(), StatementSummary.NONE), today))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), form.name().trim(), form.grade(),
                form.termsValue(), form.contactValue()),
                StatementSummary.NONE, sheets.today(sheetId.trim()));
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm form) {
        if (form.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The version of the person edited is required");
        }
        Person saved = service.edit(sheetId.trim(), user.trim(), personId, form.version(), form.name().trim(),
                form.grade(), form.gradeFrom(), form.termsValue(), form.contactValue());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
    }

    @ViewerAllowed
    @PostMapping("/grade")
    public Graded grade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeForm form) {
        String sheet = sheetId.trim();
        List<Person> saved = service.grade(sheet, user.trim(), form.personIds(), form.grade(), form.from());
        Map<Long, StatementSummary> summaries = statements.summaries(sheet);
        LocalDate today = sheets.today(sheet);
        return new Graded(saved.size(), saved.stream()
                .map(p -> PersonView.of(p, summaries.getOrDefault(p.getId(), StatementSummary.NONE), today))
                .toList());
    }

    @PostMapping("/{personId}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AliasForm form) {
        Person saved = service.addAlias(sheetId.trim(), user.trim(), personId, form.alias().trim());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
    }

    /** Merge the person into another; answers with the other as they now stand. */
    @PostMapping("/{personId}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MergeForm form) {
        Person saved = merges.merge(sheetId.trim(), user.trim(), personId, form.intoId());
        return PersonView.of(saved, statements.summary(sheetId.trim(), saved.getId()), sheets.today(sheetId.trim()));
    }

    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireForm form) {
        Person saved = service.retire(sheetId.trim(), user.trim(), personId, form.from());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
    }

    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldForm form) {
        Person saved = service.hold(sheetId.trim(), user.trim(), personId, form.from(), form.to());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
    }
}
