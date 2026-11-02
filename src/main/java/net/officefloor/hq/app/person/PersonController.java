package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementSummary;
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
     *  whether they are retired, and from which day (null when not); and whether they are on hold.
     *  The grade is the one in force on the sheet's today; grades are all those recorded, each from
     *  its from-date (null: the beginning of time), earliest first. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, long version,
            int statementCount, String statedTotal, LocalDate statementDate, boolean retired, LocalDate retiredFrom,
            boolean held) {
        public static PersonView of(Person person, StatementSummary statements, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(g -> new GradeView(g.getGrade(), g.getFromDate())).toList(),
                    person.getVersion(), statements.count(),
                    statements.lastDueTotal() == null ? null : statements.lastDueTotal().setScale(2).toPlainString(),
                    statements.lastTakenOn(), person.isRetired(), person.getRetiredFrom(), person.isHeld());
        }
    }

    /** A grade recorded for a person, from a day (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
    }

    /** The person form: a required name and a grade of A, B or C. An edit also gives the version
     *  of the person its editor loaded, and the day the grade applies from (left out, the sheet's
     *  today). */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, Long version, LocalDate gradeFrom) {
    }

    /** The retire form: the day the person is retired from; left out, the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** The hold form: whether the person is to be on hold (true) or taken off it (false). */
    public record HoldForm(@NotNull Boolean held) {
    }

    private final PersonService service;
    private final StatementService statements;
    private final SheetService sheets;

    public PersonController(PersonService service, StatementService statements, SheetService sheets) {
        this.service = service;
        this.statements = statements;
        this.sheets = sheets;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
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
        return PersonView.of(service.add(sheetId.trim(), user.trim(), form.name().trim(), form.grade()),
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
                form.grade(), form.gradeFrom());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
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
            @Valid @RequestBody HoldForm form) {
        Person saved = service.hold(sheetId.trim(), user.trim(), personId, form.held());
        return PersonView.of(saved, statements.summary(sheetId.trim(), personId), sheets.today(sheetId.trim()));
    }
}
