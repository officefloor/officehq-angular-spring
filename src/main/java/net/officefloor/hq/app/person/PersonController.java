package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/** The people on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /** A person as shown, with how many statements they have had and the due-total and date of the last one. */
    public record PersonView(Long id, String name, String grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, boolean retired, LocalDate retiredFrom, boolean held,
            List<Long> patronIds) {

        static PersonView of(Person person, StatementSummary statements, List<Long> patronIds) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    statements.count(), statements.lastDueTotal(), statements.lastDate(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(), patronIds);
        }
    }

    public record PersonRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version) {
    }

    /** The date to retire from; null for the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    /** The person to name as a patron. */
    public record PatronRequest(@NotNull Long patronId) {
    }

    private final PersonService service;
    private final StatementService statements;

    public PersonController(PersonService service, StatementService statements) {
        this.service = service;
        this.statements = statements;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, StatementSummary> summaries = statements.summaries(sheetId.trim());
        Map<Long, List<Long>> patronIds = service.patronIds(sheetId.trim());
        return service.list(sheetId.trim()).stream()
                .map(p -> PersonView.of(p, summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        patronIds.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
    }

    /** Saves the person; with a version, only when it is still the version stored. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PersonRequest request) {
        return view(service.update(sheetId.trim(), user.trim(), id, request.version(), request.name(),
                request.grade()));
    }

    /** Take a statement of the person: record their due-total and bind their counting claims to their dues. */
    @PostMapping("/{id}/statement")
    public PersonView statement(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(statements.take(sheetId.trim(), user.trim(), id));
    }

    /** Retire the person from a date. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return view(service.retire(sheetId.trim(), user.trim(), id, request.from()));
    }

    /** Put the person on hold, or take them off it. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.toggleHold(sheetId.trim(), user.trim(), id));
    }

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PatronRequest request) {
        return view(service.addPatron(sheetId.trim(), user.trim(), id, request.patronId()));
    }

    private PersonView view(Person person) {
        return PersonView.of(person, statements.summary(person.getSheetId(), person.getId()),
                service.patronIdsOf(person.getId()));
    }
}
