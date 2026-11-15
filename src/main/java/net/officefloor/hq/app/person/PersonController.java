package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerAllowed;
import net.officefloor.hq.app.sheet.SheetService;
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
     *  they are retired from (null while not retired), their past retirements, their snapshot lines (oldest first), whether they have any hold, their holds, and the ids of
     *  their patrons in the order they were named, and whether the signed-in user watches them. The grade is the one in force on the sheet's
     *  today; grades lists every grade they have been given, by from-date, the first one undated; terms
     *  are their own terms in days (null when they have none). Clashing: their name or an alias is
     *  also a name or alias on another sheet of the sheet's tree. */
    public record PersonView(long id, String name, String grade, long version, int statementCount,
            String statedTotal, LocalDate statementDate, LocalDate retiredFrom, boolean held, List<Person.Hold> holds,
            List<Long> patronIds, List<Person.DatedGrade> grades, boolean watched, Integer terms,
            String contact, List<String> aliases, boolean clashing, String origin,
            List<Person.Retirement> retirements, String tags, List<SnapshotLine> snapshots) {
        public static PersonView of(Person p, LocalDate today, StatementSummary s, List<Long> patronIds,
                boolean watched, boolean clashing) {
            return of(p.getId(), p, today, s, patronIds, watched, clashing);
        }

        /** The person of the id, which a version rebuilt from the sheet's history does not carry. */
        static PersonView of(long id, Person p, LocalDate today, StatementSummary s, List<Long> patronIds,
                boolean watched, boolean clashing) {
            return new PersonView(id, p.getName(), p.gradeOn(today), p.getVersion(), s.count(),
                    s.lastDueTotal() == null ? null : s.lastDueTotal().setScale(2).toPlainString(), s.lastDate(),
                    p.getRetiredFrom(), p.isHeld(), p.getHolds(), patronIds, p.getGrades(), watched, p.getTerms(),
                    p.getContact(), p.getAliases(), clashing, p.getOriginSheetId(), p.getRetirements(),
                    p.getTags(), p.getSnapshots().stream().map(SnapshotLine::of).toList());
        }
    }

    /** A snapshot line as the screen shows it: the day being left and the person's due-total on it
     *  (two decimals; null when it was n/a). */
    public record SnapshotLine(LocalDate day, String dueTotal) {
        static SnapshotLine of(Person.Snapshot s) {
            return new SnapshotLine(s.day(), s.dueTotal() == null ? null : s.dueTotal().setScale(2).toPlainString());
        }
    }

    /** A person's values as the form submits them; the name is stored with surrounding spaces removed.
     *  An edit carries the version of the person its editor loaded, and the date its grade is in
     *  force from (null: the sheet's today); an add's grade is in force from the beginning of time.
     *  The terms are the person's own in whole days, blank or null for none. The contact is stored
     *  trimmed and lower-cased; blank or null for none. The tags are comma separated, taken as a
     *  claim's are; blank or null for none. */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            Long version,
            LocalDate gradeFrom,
            @Pattern(regexp = "\\s*\\d{0,4}\\s*") String terms,
            @Size(max = 200) String contact,
            @Size(max = 1000) String tags) {

        /** The terms in days; null when blank. */
        public Integer termsDays() {
            return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
        }

        /** The tags as stored: lower-case, sorted and comma separated, without repeats; null when none. */
        public String tagsValue() {
            return ClaimService.tagsOf(tags);
        }

        /** The contact as stored: trimmed and lower-cased; null when blank. */
        public String contactValue() {
            return contact == null || contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** An alias as the row submits it; stored trimmed. */
    public record AliasForm(@Size(max = 200) String alias) {
    }

    /** A retirement as the row submits it; a missing date takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A hold as the row submits it: a missing from-date takes the sheet's today; a missing to-date
     *  leaves it open-ended. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A move as the row submits it: up (towards the top of the list) or down. */
    public record MoveForm(@NotNull @Pattern(regexp = "up|down") String direction) {
    }

    /** A watch as the row submits it: true to start watching, false to stop. */
    public record WatchForm(@NotNull Boolean on) {
    }

    /** A patron as the row submits it: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** A merge as the row submits it: the id of the person on the sheet to merge into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** A transfer as the row submits it: the id of the sheet to transfer the person to. */
    public record TransferForm(@NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String toSheetId) {
    }

    /** A grade as the row submits it: in force from the sheet's today. */
    public record GradeForm(@NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** A bulk grade as the screen submits it: the selected people by id, the grade, and the date it
     *  is in force from (null: the sheet's today). */
    public record BulkGradeForm(
            @NotNull @Size(min = 1, max = 10000) List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** A bulk grade's result: how many people were given the grade. */
    public record BulkGradeView(int changed) {
    }

    /** A bulk retire as the screen submits it: the selected people by id, and the date they are
     *  retired from (null: the sheet's today). */
    public record BulkRetireForm(
            @NotNull @Size(min = 1, max = 10000) List<@NotNull Long> ids,
            LocalDate from) {
    }

    /** A bulk retire's result: how many people were retired. */
    public record BulkRetireView(int retired) {
    }

    private final PersonService service;
    private final HistoryService history;
    private final SettingsService settings;
    private final SheetService sheets;

    public PersonController(PersonService service, HistoryService history, SettingsService settings, SheetService sheets) {
        this.service = service;
        this.history = history;
        this.settings = settings;
        this.sheets = sheets;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            HistoryService.AsOf then = history.asOf(sheetId, asOf);
            Set<String> treeNames = service.treeNames(sheetId);
            return then.people().keySet().stream().map(id -> viewAsOf(then, asOf, user, id, treeNames)).toList();
        }
        Map<Long, StatementSummary> summaries = service.statementSummaries(sheetId);
        Map<Long, List<Long>> patrons = service.patronIds(sheetId);
        LocalDate today = service.today(sheetId);
        Set<Long> watched = service.watchedIds(sheetId, user.trim());
        Set<String> treeNames = service.treeNames(sheetId);
        return service.list(sheetId).stream()
                .map(p -> PersonView.of(p, today, summaries.getOrDefault(p.getId(), StatementSummary.NONE),
                        patrons.getOrDefault(p.getId(), List.of()), watched.contains(p.getId()),
                        PersonService.clashes(p, treeNames)))
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
            return viewAsOf(then, asOf, user, id, service.treeNames(sheetId));
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
                body.termsDays(), body.contactValue(), body.tagsValue()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm body) {
        return view(sheetId, user, service.update(sheetId, user.trim(), id, body.version(), body.name(), body.grade(),
                body.gradeFrom(), body.termsDays(), body.contactValue(), body.tagsValue()));
    }

    /** Give the person the grade from the sheet's today, as saving them in the person form would. */
    @PostMapping("/{id}/grade")
    public PersonView grade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeForm body) {
        service.grade(sheetId, user.trim(), List.of(id), body.grade(), null);
        return view(sheetId, user, service.get(sheetId, id));
    }

    /** Give the selected people the grade; a viewer, who may change nothing, changes no one. */
    @PostMapping("/bulk-grade")
    @ViewerAllowed
    public BulkGradeView bulkGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkGradeForm body) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        if (settings.isViewer(sheetId, user.trim())) {
            return new BulkGradeView(0);
        }
        return new BulkGradeView(service.grade(sheetId, user.trim(), body.ids(), body.grade(), body.from()));
    }

    /** Retire the selected people from the date; a viewer, who may change nothing, retires no one. */
    @PostMapping("/bulk-retire")
    @ViewerAllowed
    public BulkRetireView bulkRetire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkRetireForm body) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        if (settings.isViewer(sheetId, user.trim())) {
            return new BulkRetireView(0);
        }
        return new BulkRetireView(service.retire(sheetId, user.trim(), body.ids(), body.from()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) RetireForm body) {
        return view(sheetId, user, service.retire(sheetId, user.trim(), id, body == null ? null : body.from()));
    }

    @PostMapping("/{id}/unretire")
    public PersonView unretire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return view(sheetId, user, service.unretire(sheetId, user.trim(), id));
    }

    /** Move the person one place up or down the list; answers with the sheet's people in their new order. */
    @PostMapping("/{id}/move")
    public List<PersonView> move(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MoveForm body) {
        service.move(sheetId, user.trim(), id, body.direction().equals("up"));
        return list(sheetId, user, null);
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

    @PostMapping("/{id}/aliases")
    public PersonView addAlias(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AliasForm body) {
        return view(sheetId, user, service.addAlias(sheetId, user.trim(), id, body.alias()));
    }

    @PostMapping("/{id}/patrons")
    public PersonView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PatronForm body) {
        return view(sheetId, user, service.addPatron(sheetId, user.trim(), id, body.patronId()));
    }

    /** Merge the person into another; answers with the person merged into. */
    @PostMapping("/{id}/merge")
    public PersonView merge(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MergeForm body) {
        return view(sheetId, user, service.merge(sheetId, user.trim(), id, body.intoId()));
    }

    /** Transfer the person to another sheet; answers with the person as they arrived there. */
    @PostMapping("/{id}/transfer")
    public PersonView transfer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TransferForm body) {
        Person arrived = service.transfer(sheetId, user.trim(), id, body.toSheetId());
        return PersonView.of(arrived, service.today(arrived.getSheetId()), service.statementSummary(arrived.getId()), List.of(), false,
                service.clashing(arrived));
    }

    /** Start or stop watching the person as the signed-in user; refused for a viewer, who may change nothing. */
    @PostMapping("/{id}/watch")
    public PersonView watch(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody WatchForm body) {
        return view(sheetId, user, service.watch(sheetId, user.trim(), id, body.on()));
    }

    /** The person of the id as they stood at the end of {@code asOf}, with the statements, patrons
     *  and watches there were then; clashing against the names the sheet's tree has now. */
    private static PersonView viewAsOf(HistoryService.AsOf then, LocalDate asOf, String user, long id,
            Set<String> treeNames) {
        List<PersonStatement> theirs = then.statements().values().stream()
                .filter(s -> s.getPersonId() == id).toList();
        List<Long> patronIds = then.patrons().stream().filter(p -> p.getPersonId() == id)
                .map(PersonPatron::getPatronId).collect(Collectors.toList());
        boolean watched = then.watches().stream()
                .anyMatch(w -> w.getPersonId() == id && w.getUserName().equals(user.trim()));
        Person p = then.people().get(id);
        return PersonView.of(id, p, asOf, StatementSummary.of(theirs), patronIds, watched,
                PersonService.clashes(p, treeNames));
    }

    private PersonView view(String sheetId, String user, Person p) {
        return PersonView.of(p, service.today(sheetId), service.statementSummary(p.getId()), service.patronIds(p.getId()),
                service.isWatching(p.getId(), user.trim()), service.clashing(p));
    }
}
