package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.ViewerAllowed;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A person as the screen shows them, with their statement count and their last statement's
     *  due-total (two decimals; null when it was n/a or they have none) and date, and the date
     *  they are retired from (null while not retired), whether they have any hold, their holds, and the ids of
     *  their patrons in the order they were named, and whether the signed-in user watches them. The grade is the one in force on the sheet's
     *  today; grades lists every grade they have been given, by from-date, the first one undated; terms
     *  are their own terms in days (null when they have none). */
    public record PersonView(long id, String name, String grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held, List<Person.Hold> holds,
            List<Long> patronIds, List<Person.DatedGrade> grades, boolean watched, Integer terms) {
        public static PersonView of(Person p, LocalDate today, StatementSummary s, List<Long> patronIds,
                boolean watched) {
            return of(p.getId(), p, today, s, patronIds, watched);
        }

        /** The person of the id, which a version rebuilt from the sheet's history does not carry. */
        static PersonView of(long id, Person p, LocalDate today, StatementSummary s, List<Long> patronIds,
                boolean watched) {
            return new PersonView(id, p.getName(), p.gradeOn(today), p.getVersion(), s.count(),
                    s.lastDueTotal() == null ? null : s.lastDueTotal().setScale(2).toPlainString(), s.lastDate(),
                    p.getRetiredFrom(), p.isHeld(), p.getHolds(), patronIds, p.getGrades(), watched, p.getTerms());
        }
    }

    /** A person's values as the form submits them; the name is stored with surrounding spaces removed.
     *  An edit carries the version of the person its editor loaded, and the date its grade is in
     *  force from (null: the sheet's today); an add's grade is in force from the beginning of time.
     *  The terms are the person's own in whole days, blank or null for none. */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\s*\\d{0,4}\\s*") String terms) {

        /** The terms in days; null when blank. */
        public Integer termsDays() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }
    }

    /** A retirement as the row submits it; a missing date takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A hold as the row submits it: a missing from-date takes the sheet's today; a missing to-date
     *  leaves it open-ended. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A watch as the row submits it: true to start watching, false to stop. */
    public record WatchForm(@NotNull Boolean on) {
    }

    /** A patron as the row submits it: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    private final PersonService service;
    private final HistoryService history;

    public PersonController(PersonService service, HistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            HistoryService.AsOf then = history.asOf(sheetId, asOf);
            return then.people().keySet().stream().map(id -> viewAsOf(then, asOf, user, id)).toList();
        }
        Map<Long, StatementSummary> summaries = service.statementSummaries(sheetId);
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        LocalDate today = service.today(sheetId);
        Set<Long> watched = service.watchedIds(sheetId, user.trim());
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        patrons.getOrDefault(p.getId(), List.of()), watched.contains(p.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    public PersonView get(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            HistoryService.AsOf then = history.asOf(sheetId, asOf);
            if (!then.people().containsKey(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
            }
            return viewAsOf(then, asOf, user, id);
        }
        return view(sheetId, user, service.get(sheetId, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm body) {
        return view(sheetId, user, service.add(sheetId, user.trim(), body.name(), body.grade(),
                body.termsDays()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm body) {
        return view(sheetId, user, service.update(sheetId, user.trim(), id, body.version(), body.name(), body.grade(),
                body.gradeFrom(), body.termsDays()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) RetireForm body) {
        return view(sheetId, user, service.retire(sheetId, user.trim(), id, body == null ? null : body.from()));
    }

    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) HoldForm body) {
        return view(sheetId, user, service.hold(sheetId, user.trim(), id, body == null ? null : body.from(),
                body == null ? null : body.to()));
    }

    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PatronForm body) {
        return view(sheetId, user, service.addPatron(sheetId, user.trim(), id, body.patronId()));
    }

    /** Start or stop watching the person as the signed-in user; a viewer may watch too. */
    @PostMapping("/{id}/watch")
    @ViewerAllowed
    public PersonView watch(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody WatchForm body) {
        return view(sheetId, user, service.watch(sheetId, user.trim(), id, body.on()));
    }

    /** The person of the id as they stood at the end of {@code asOf}, with the statements, patrons
     *  and watches there were then. */
    private static PersonView viewAsOf(HistoryService.AsOf then, LocalDate asOf, String user, long id) {
        List<PersonStatement> theirs = then.statements().values().stream()
                .filter(s -> s.getPersonId() == id).toList();
        List<Long> patronIds = then.patrons().stream().filter(p -> p.getPersonId() == id)
                .map(PersonPatron::getPatronId).collect(Collectors.toList());
        boolean watched = then.watches().stream()
                .anyMatch(w -> w.getPersonId() == id && w.getUserName().equals(user.trim()));
        return PersonView.of(id, then.people().get(id), asOf, StatementSummary.of(theirs), patronIds, watched);
    }

    private PersonView view(String sheetId, String user, Person p) {
        return PersonView.of(p, service.today(sheetId), service.statementSummary(p.getId()), service.patronIds(p.getId()),
                service.isWatching(p.getId(), user.trim()));
    }
}
