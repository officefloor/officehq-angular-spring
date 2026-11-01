package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /**
     * A person as the screen shows them: grade is the one in force on the sheet's today, grades every
     * grade recorded, earliest first (the first from null, the beginning of time); retiredFrom is null
     * while not retired; patronIds in the order named.
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<Long> patronIds) {
        public static PersonView of(Person person, LocalDate today, List<Long> patronIds) {
            List<GradeView> grades = new ArrayList<>();
            grades.add(new GradeView(null, person.getGrade()));
            person.getDatedGrades().forEach((from, grade) -> grades.add(new GradeView(from, grade)));
            return new PersonView(person.getId(), person.getName(),
                    today == null ? person.getGrade() : person.gradeOn(today), grades, person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(), patronIds);
        }
    }

    /** A grade in force from a date (null: the beginning of time). */
    public record GradeView(LocalDate from, String grade) {}

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {}

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {}

    /** An edit, made from the version of the person the form loaded. */
    public record PersonEditRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @NotNull Long version) {}

    /** The date a person is retired from (blank: the sheet's today). */
    public record RetireRequest(LocalDate from) {}

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        LocalDate today = service.today(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, patrons.getOrDefault(p.getId(), List.of()))).toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.add(sheetId, user, request.name(), request.grade()));
    }

    @PutMapping("/{id}")
    public PersonView edit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonEditRequest request) {
        return view(service.edit(sheetId, user, id, request.version(), request.name(), request.grade(),
                request.gradeFrom()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(service.retire(sheetId, user, id, request.from()));
    }

    /** Put the person on hold, or take them off it when held. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.toggleHold(sheetId, user, id));
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId, user, id, request.patronId()));
    }

    private PersonView view(Person person) {
        return PersonView.of(person, service.today(person.getSheetId()), service.patronIdsOf(person.getId()));
    }
}
