package net.officefloor.hq.app.person;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

/** People on a sheet. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /** A grade recorded for a person; from is null for the first one, in force from the beginning of time. */
    public record GradeView(LocalDate from, String grade) {
    }

    /** A hold on a person, from its from-date to its to-date (both included), or on when to is null. */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /**
     * Held is whether the person has any hold; holds are all those recorded, by from-date.
     * The version is the one an edit sends back, so a save made from an out-of-date copy is refused.
     * The grade is the one in force on the sheet's today; grades are all those recorded, oldest first.
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            LocalDate retiredFrom, boolean held, List<HoldView> holds, List<Long> patronIds) {
        static PersonView of(Person person, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(g -> new GradeView(g.getFrom(), g.getGrade())).toList(),
                    person.getVersion(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFrom(), h.getTo())).toList(),
                    person.getPatronIds().stream().sorted().toList());
        }
    }

    /** The name is stored trimmed; one that is empty or only spaces is refused. */
    public record PersonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** An edit carries the version of the person it was made from, and the date its grade applies from
     * (the sheet's today when none is given). */
    public record PersonUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @NotNull Long version) {
    }

    /** Retire a person from a date; a missing date is the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    /** Put a person on hold from a date (the sheet's today when missing) to an optional to-date. */
    public record HoldRequest(LocalDate from, LocalDate to) {
    }

    /** Name another person on the sheet as a patron. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    /** Lines of name,grade pasted into the import box. */
    public record ImportRequest(@NotNull @Size(max = 100_000) String text) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        LocalDate today = service.today(sheetId.trim());
        return service.list(sheetId.trim()).stream().map(person -> PersonView.of(person, today)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PersonRequest request) {
        return view(sheetId, service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
    }

    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PersonUpdateRequest request) {
        return view(sheetId, service.update(sheetId.trim(), user.trim(), personId, request.version(),
                request.name(), request.grade(), request.gradeFrom()));
    }

    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(sheetId, service.retire(sheetId.trim(), user.trim(), personId, request.from()));
    }

    @PostMapping("/{personId}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldRequest request) {
        return view(sheetId, service.hold(sheetId.trim(), user.trim(), personId, request.from(), request.to()));
    }

    @PostMapping("/{personId}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PatronRequest request) {
        return view(sheetId, service.addPatron(sheetId.trim(), user.trim(), personId, request.patronId()));
    }

    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId.trim()));
    }

    @PostMapping("/import")
    public PersonService.ImportResult importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated ImportRequest request) {
        return service.importPeople(sheetId.trim(), user.trim(), request.text());
    }
}
