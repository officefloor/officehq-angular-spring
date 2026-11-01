package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.setting.OpenToViewer;
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
     * while not retired; held whether they have any hold, holds every hold, earliest first; patronIds
     * in the order named; watched whether the signed-in user watches them; terms their own, null for none;
     * contact theirs, null for none; aliases theirs, sorted as names are compared; active whether they are active on the sheet's today ({@link Person#activeOn}).
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, List<Long> patronIds,
            boolean watched, Integer terms, boolean active, String contact, List<String> aliases) {
        public static PersonView of(Person person, LocalDate today, List<Long> patronIds, boolean watched,
                boolean active) {
            List<GradeView> grades = new ArrayList<>();
            grades.add(new GradeView(null, person.getGrade()));
            person.getDatedGrades().forEach((from, grade) -> grades.add(new GradeView(from, grade)));
            return new PersonView(person.getId(), person.getName(),
                    today == null ? person.getGrade() : person.gradeOn(today), grades, person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFromDate(), h.getToDate())).toList(),
                    patronIds, watched, person.getTerms(), active, person.getContact(),
                    person.getAliases());
        }
    }

    /** A grade in force from a date (null: the beginning of time). */
    public record GradeView(LocalDate from, String grade) {}

    /** A hold from a date (null: the beginning of time) to a date (null: open-ended), both included. */
    public record HoldView(LocalDate from, LocalDate to) {}

    /** A hold to put on a person: from the date (blank: the sheet's today) to the optional to-date. */
    public record HoldRequest(LocalDate from, LocalDate to) {}

    /** Whether the signed-in user is to watch the person. */
    public record WatchRequest(@NotNull Boolean watched) {}

    /** An alias to give a person. */
    public record AliasRequest(@NotBlank @Size(max = 200) String alias) {}

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {}

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @Pattern(regexp = "\\d{0,4}") String terms,
            @Size(max = 200) String contact) {}

    /** An edit, made from the version of the person the form loaded. */
    public record PersonEditRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\d{0,4}") String terms,
            @Size(max = 200) String contact,
            @NotNull Long version) {}

    /** The people to give the grade, from the date (blank: the sheet's today). */
    public record BulkGradeRequest(
            @NotEmpty List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {}

    /** How many people a bulk grade changed. */
    public record BulkGradeResult(int graded) {}

    /** Terms in days as the form gave them; blank or null: none. */
    private static Integer termsOf(String terms) {
        return terms == null || terms.isEmpty() ? null : Integer.valueOf(terms);
    }

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
        Set<Long> watched = service.watchedIds(sheetId, user);
        LocalDate today = service.today(sheetId);
        Set<Long> active = service.activeIds(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, patrons.getOrDefault(p.getId(), List.of()),
                        watched.contains(p.getId()), active.contains(p.getId())))
                .toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.add(sheetId, user, request.name(), request.grade(), termsOf(request.terms()),
                request.contact()), user);
    }

    @PutMapping("/{id}")
    public PersonView edit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonEditRequest request) {
        return view(service.edit(sheetId, user, id, request.version(), request.name(), request.grade(),
                request.gradeFrom(), termsOf(request.terms()), request.contact()), user);
    }

    /** Give each selected person the grade from the date (blank: the sheet's today), in list order. */
    @OpenToViewer
    @PostMapping("/grade")
    public BulkGradeResult bulkGrade(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkGradeRequest request) {
        return new BulkGradeResult(service.bulkGrade(sheetId, user, Set.copyOf(request.ids()), request.grade(),
                request.from()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(service.retire(sheetId, user, id, request.from()), user);
    }

    /** Put the person on hold from a date to an optional to-date. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldRequest request) {
        return view(service.hold(sheetId, user, id, request.from(), request.to()), user);
    }

    /** Give the person an alias. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AliasRequest request) {
        return view(service.addAlias(sheetId, user, id, request.alias()), user);
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId, user, id, request.patronId()), user);
    }

    /** Start or stop watching the person as the signed-in user. */
    @PutMapping("/{id}/watch")
    public PersonView watch(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid WatchRequest request) {
        return view(service.watch(sheetId, user, id, request.watched()), user);
    }

    private PersonView view(Person person, String user) {
        return PersonView.of(person, service.today(person.getSheetId()), service.patronIdsOf(person.getId()),
                service.watches(person.getId(), user), service.isActive(person));
    }
}
