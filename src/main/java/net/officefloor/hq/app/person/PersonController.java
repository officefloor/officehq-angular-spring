package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /**
     * A person, with how many statements were taken for them and the last one's due-total (text with
     * two decimals, "n/a" when it had none) and date; both null before their first statement.
     */
    public record PersonView(long id, String name, Grade grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held,
            List<PersonService.PatronOf> patrons) {
        static PersonView of(Person person, PersonService.Statements statements,
                List<PersonService.PatronOf> patrons) {
            Statement last = statements.last();
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    statements.count(), last == null ? null
                            : last.getDueTotal() == null ? "n/a" : last.getDueTotal().setScale(2).toPlainString(),
                    last == null ? null : last.getTakenOn(), person.getRetiredFrom(), person.isHeld(), patrons);
        }
    }

    /** The version is the one the editor loaded; required to edit, ignored when adding. */
    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade, Long version) {}

    /** The date a person is retired from; without one, the sheet's today. */
    public record RetireForm(LocalDate from) {}

    /** The person to name as a patron. */
    public record PatronForm(@NotNull Long patronId) {}

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    /** The sheet's people, in the order they were added. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        Map<Long, PersonService.Statements> statements = service.statements(sheetId);
        Map<Long, List<PersonService.PatronOf>> patrons = service.patrons(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, statements.getOrDefault(p.getId(), PersonService.Statements.NONE),
                        patrons.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** Add a person. */
    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        return view(sheetId, service.add(sheetId, user, form.name(), form.grade()));
    }

    /** Change a person's name and grade, refused when someone else saved them since that version. */
    @PutMapping("/{personId}")
    public PersonView edit(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        if (form.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The version being edited is required");
        }
        return view(sheetId, service.edit(sheetId, user, personId, form.version(), form.name(), form.grade()));
    }

    /** Retire a person from a date. */
    @PostMapping("/{personId}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @RequestBody(required = false) RetireForm form) {
        return view(sheetId, service.retire(sheetId, user, personId, form == null ? null : form.from()));
    }

    /** Put a person on hold, or take them off hold when they are held. */
    @PostMapping("/{personId}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return view(sheetId, service.toggleHold(sheetId, user, personId));
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{personId}/patrons")
    public PersonView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PatronForm form) {
        return view(sheetId, service.addPatron(sheetId, user, personId, form.patronId()));
    }

    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person,
                service.statements(sheetId).getOrDefault(person.getId(), PersonService.Statements.NONE),
                service.patrons(sheetId).getOrDefault(person.getId(), List.of()));
    }
}
