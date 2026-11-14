package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.session.CurrentUser;
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

    /** A person as the screen shows them, with their statement count and their last statement's
     *  due-total (two decimals; null when it was n/a or they have none) and date, and the date
     *  they are retired from (null while not retired), whether they have any hold, their holds, and the ids of
     *  their patrons in the order they were named. The grade is the one in force on the sheet's
     *  today; grades lists every grade they have been given, by from-date, the first one undated. */
    public record PersonView(long id, String name, String grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held, List<Person.Hold> holds,
            List<Long> patronIds, List<Person.DatedGrade> grades) {
        public static PersonView of(Person p, LocalDate today, StatementSummary s, List<Long> patronIds) {
            return new PersonView(p.getId(), p.getName(), p.gradeOn(today), p.getVersion(), s.count(),
                    s.lastDueTotal() == null ? null : s.lastDueTotal().setScale(2).toPlainString(), s.lastDate(),
                    p.getRetiredFrom(), p.isHeld(), p.getHolds(), patronIds, p.getGrades());
        }
    }

    /** A person's values as the form submits them; the name is stored with surrounding spaces removed.
     *  An edit carries the version of the person its editor loaded, and the date its grade is in
     *  force from (null: the sheet's today); an add's grade is in force from the beginning of time. */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version,
            LocalDate gradeFrom) {
    }

    /** A retirement as the row submits it; a missing date takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A hold as the row submits it: a missing from-date takes the sheet's today; a missing to-date
     *  leaves it open-ended. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A patron as the row submits it: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        Map<Long, StatementSummary> summaries = service.statementSummaries(sheetId);
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        LocalDate today = service.today(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        patrons.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @GetMapping("/{id}")
    public PersonView get(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(sheetId, service.get(sheetId, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm body) {
        return view(sheetId, service.add(sheetId, user.trim(), body.name(), body.grade()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm body) {
        return view(sheetId, service.update(sheetId, user.trim(), id, body.version(), body.name(), body.grade(),
                body.gradeFrom()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) RetireForm body) {
        return view(sheetId, service.retire(sheetId, user.trim(), id, body == null ? null : body.from()));
    }

    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) HoldForm body) {
        return view(sheetId, service.hold(sheetId, user.trim(), id, body == null ? null : body.from(),
                body == null ? null : body.to()));
    }

    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PatronForm body) {
        return view(sheetId, service.addPatron(sheetId, user.trim(), id, body.patronId()));
    }

    private PersonView view(String sheetId, Person p) {
        return PersonView.of(p, service.today(sheetId), service.statementSummary(p.getId()), service.patronIds(p.getId()));
    }
}
