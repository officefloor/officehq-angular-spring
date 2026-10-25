package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.sheet.SheetService;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /** A grade the person was given, from its from-date (null: from the beginning of time). */
    public record GradeView(Grade grade, LocalDate from) {
    }

    /** What the screen shows of a person: grade is the one in force on the sheet's today, grades every
     *  grade they were given, the one from the beginning of time first, then by from-date. */
    public record PersonView(long id, String name, Grade grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<Long> patronIds) {
        /** A person with no patrons, with the grade in force on the sheet's today. */
        public static PersonView of(Person person, LocalDate today) {
            return of(person, List.of(), today);
        }

        /** A person with the ids of their patrons, in the order they were named, and the grade in force
         *  on the sheet's today. */
        public static PersonView of(Person person, List<Long> patronIds, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(g -> new GradeView(g.getGrade(), g.getFrom())).toList(),
                    person.getVersion(), person.isRetired(), person.getRetiredFrom(), person.isHeld(), patronIds);
        }
    }

    /** A person's fields, as entered on the form; a new person's grade applies from the beginning of time. */
    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade) {
    }

    /** A person's fields as edited, with the version of the person the editor loaded; the grade is
     *  given from the from-date, none meaning the sheet's today. */
    public record PersonEdit(@NotBlank @Size(max = 200) String name, @NotNull Grade grade, LocalDate gradeFrom,
            @NotNull Long version) {
    }

    /** The person named as a patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** A retirement; no date means the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    private final PersonService service;
    private final SheetService sheets;

    public PersonController(PersonService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        Map<Long, List<Long>> patronIds = service.patronIds(sheet);
        LocalDate today = sheets.today(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, patronIds.getOrDefault(p.getId(), List.of()), today)).toList();
    }

    /** Add a person. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonForm form) {
        Person added = service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.grade());
        return PersonView.of(added, sheets.today(added.getSheetId()));
    }

    /** Edit a person. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonEdit form) {
        return view(service.update(sheetId.trim(), SignedInUser.name(user), id, form.version(),
                form.name(), form.grade(), form.gradeFrom()));
    }

    /** Retire a person from a date. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody RetireForm form) {
        return view(service.retire(sheetId.trim(), SignedInUser.name(user), id, form.from()));
    }

    /** Put a person on hold. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return view(service.hold(sheetId.trim(), SignedInUser.name(user), id, true));
    }

    /** Take a person off hold. */
    @PostMapping("/{id}/release")
    public PersonView release(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return view(service.hold(sheetId.trim(), SignedInUser.name(user), id, false));
    }

    /** Name another person on the sheet as one of the person's patrons. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PatronForm form) {
        return view(service.addPatron(sheetId.trim(), SignedInUser.name(user), id, form.patronId()));
    }

    private PersonView view(Person person) {
        return PersonView.of(person, service.patronIdsOf(person.getId()), sheets.today(person.getSheetId()));
    }
}
