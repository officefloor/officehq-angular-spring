package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.SignedInUser;
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

    /** What the screen shows of a person. */
    public record PersonView(long id, String name, Grade grade, long version, boolean retired, LocalDate retiredFrom,
            boolean held, List<Long> patronIds) {
        /** A person with no patrons. */
        public static PersonView of(Person person) {
            return of(person, List.of());
        }

        /** A person with the ids of their patrons, in the order they were named. */
        public static PersonView of(Person person, List<Long> patronIds) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(), patronIds);
        }
    }

    /** A person's fields, as entered on the form. */
    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade) {
    }

    /** A person's fields as edited, with the version of the person the editor loaded. */
    public record PersonEdit(@NotBlank @Size(max = 200) String name, @NotNull Grade grade, @NotNull Long version) {
    }

    /** The person named as a patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** A retirement; no date means the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        Map<Long, List<Long>> patronIds = service.patronIds(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, patronIds.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Add a person. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonForm form) {
        return PersonView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.grade()));
    }

    /** Edit a person. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonEdit form) {
        return view(service.update(sheetId.trim(), SignedInUser.name(user), id, form.version(),
                form.name(), form.grade()));
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
        return PersonView.of(person, service.patronIdsOf(person.getId()));
    }
}
