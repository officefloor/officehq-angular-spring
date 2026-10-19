package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.statement.Statement;
import net.officefloor.hq.app.statement.StatementService;
import net.officefloor.hq.app.statement.StatementService.Summary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people kept on a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version) {}

    /** A person shows how many statements were taken for them and the last one's due-total (n/a when it was)
     * and date; none of those before the first; and the ids of their patrons, in the order they were named. */
    public record PersonView(Long id, String name, String grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held,
            List<Long> patronIds) {

        public static PersonView of(Person person, Map<Long, Summary> statements, Map<Long, List<Long>> patrons) {
            Summary summary = statements.get(person.getId());
            Statement last = summary != null ? summary.last() : null;
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    summary != null ? summary.count() : 0,
                    last == null ? null : last.getDueTotal() != null ? last.getDueTotal().toPlainString() : "n/a",
                    last != null ? last.getTakenOn() : null, person.getRetiredFrom(), person.isHeld(),
                    patrons.getOrDefault(person.getId(), List.of()));
        }
    }

    private final PersonService service;
    private final StatementService statements;

    public PersonController(PersonService service, StatementService statements) {
        this.service = service;
        this.statements = statements;
    }

    @GetMapping
    public List<PersonView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        List<Person> people = service.list(sheetId);
        Map<Long, Summary> taken = statements.summaries(sheetId);
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        return people.stream().map(p -> PersonView.of(p, taken, patrons)).toList();
    }

    @PostMapping
    public PersonView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.add(user, sheetId, form.name(), form.grade()), statements.summaries(sheetId),
                service.patronIds(sheetId));
    }

    @PutMapping("/{id}")
    public PersonView update(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonForm form) {
        return PersonView.of(service.update(user, sheetId, id, form.name(), form.grade(), form.version()),
                statements.summaries(sheetId),
                service.patronIds(sheetId));
    }

    public record RetireForm(LocalDate from) {}

    /** Retire the person from a date, the sheet's today when none is given. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @RequestBody RetireForm form) {
        return PersonView.of(service.retire(user, sheetId, id, form.from()), statements.summaries(sheetId),
                service.patronIds(sheetId));
    }

    /** Put the person on hold, or take them off it when held. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id) {
        return PersonView.of(service.toggleHold(user, sheetId, id), statements.summaries(sheetId),
                service.patronIds(sheetId));
    }

    public record PatronForm(@NotNull Long patronId) {}

    /** Name another person on the sheet as the person's patron. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody PatronForm form) {
        return PersonView.of(service.addPatron(user, sheetId, id, form.patronId()), statements.summaries(sheetId),
                service.patronIds(sheetId));
    }

    /** Take a statement for the person. */
    @PostMapping("/{id}/statement")
    public PersonView statement(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id) {
        return PersonView.of(statements.take(user, sheetId, id), statements.summaries(sheetId),
                service.patronIds(sheetId));
    }
}
