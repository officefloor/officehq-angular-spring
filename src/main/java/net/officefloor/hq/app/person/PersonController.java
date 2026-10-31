package net.officefloor.hq.app.person;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.role.RoleService;
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

/** People on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /** A person as listed: {@code grade} is the one in force on the sheet's today; {@code grades} all those
     *  recorded, in the order they take effect; {@code clashing} whether their name or an alias is used on another
     *  sheet of the sheet's tree; {@code origin} the sheet they were transferred from (null: none). */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, Integer terms,
            long version, boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds, String contact,
            List<String> aliases, boolean clashing, String origin) {

        public static PersonView of(Person person, LocalDate today) {
            return of(person, today, false);
        }

        public static PersonView of(Person person, LocalDate today, boolean clashing) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(GradeView::of).toList(), person.getTerms(), person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(HoldView::of).toList(), person.getContact(),
                    person.getAliases(), clashing, person.getOriginSheetId());
        }
    }

    /** A grade recorded for a person, in force from {@code from} (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {

        static GradeView of(GradeFrom grade) {
            return new GradeView(grade.getGrade(), grade.getFromDate());
        }
    }

    /** A hold on a person from {@code from} to {@code to}, both inclusive (a null to-date: no end). */
    public record HoldView(LocalDate from, LocalDate to) {

        static HoldView of(Hold hold) {
            return new HoldView(hold.getFromDate(), hold.getToDate());
        }
    }

    /** A hold as the form submits it: a missing from-date takes the sheet's today; a missing to-date has no end. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A person's values as the form submits them; the name is stored with surrounding spaces removed; missing
     *  terms: none of their own; the contact is stored trimmed and lower-cased (blank or missing: none). */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @Min(0) @Max(36500) Integer terms,
            @Size(max = 200) String contact) {
    }

    /** An edit of a person: their new values, the date the grade is from (missing: the sheet's today), their own
     *  terms (missing: none), their contact (blank or missing: none) and the version of the person the editor
     *  loaded. */
    public record PersonEdit(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Min(0) @Max(36500) Integer terms,
            @NotNull Long version,
            @Size(max = 200) String contact) {
    }

    /** An alias as the row submits it; stored trimmed, and refused when blank. */
    public record AliasForm(@Size(max = 200) String alias) {
    }

    /** A merge: the person the merged one is merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** A transfer: the id of the sheet the person is transferred to. */
    public record TransferForm(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String toSheetId) {
    }

    /** A retirement: the date the person is retired from; missing takes the sheet's today. */
    public record Retirement(LocalDate from) {
    }

    /** A move of a person one place up or down the list. */
    public record Move(@NotNull @Pattern(regexp = "up|down") String direction) {
    }

    /** People selected to be given a grade together, from {@code from} (missing: the sheet's today). */
    public record BulkGrade(
            @NotNull @Size(max = 1000) List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** What a bulk grade did: how many people it changed, and those people as saved, in list order. */
    public record BulkGradeView(int changed, List<PersonView> people) {
    }

    /** People selected to be retired together, from {@code from} (missing: the sheet's today). */
    public record BulkRetire(@NotNull @Size(max = 1000) List<@NotNull Long> ids, LocalDate from) {
    }

    /** What a bulk retire did: how many people it retired, and those people as saved, in list order. */
    public record BulkRetireView(int retired, List<PersonView> people) {
    }

    private final PersonService people;
    private final SheetHistory history;
    private final RoleService roles;
    private final PersonMergeService merges;
    private final PersonTransferService transfers;

    public PersonController(PersonService people, SheetHistory history, RoleService roles,
            PersonMergeService merges, PersonTransferService transfers) {
        this.people = people;
        this.transfers = transfers;
        this.merges = merges;
        this.history = history;
        this.roles = roles;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.peopleAsOf(sheetId, asOf);
        }
        LocalDate today = people.today(sheetId);
        Set<String> used = people.usedInTree(sheetId);
        return people.list(sheetId).stream().map(p -> PersonView.of(p, today, PersonService.clashes(p, used))).toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm request) {
        Person added = people.add(sheetId, user, request.name(), request.grade(), request.terms(),
                request.contact());
        return view(sheetId, added);
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonEdit request) {
        Person edited = people.update(sheetId, user, id, request.version(), request.name(), request.grade(),
                request.gradeFrom(), request.terms(), request.contact());
        return view(sheetId, edited);
    }

    /**
     * Give the selected people a grade together: each, in list order, gets it from the date given as an edit of
     * them in the person form would, keeping their name and terms; one not on the sheet, or saved by someone else
     * meanwhile, is skipped.
     */
    @PostMapping("/bulk-grade")
    public BulkGradeView bulkGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkGrade request) {
        if (roles.isViewer(sheetId, user)) {
            // A viewer's bulk action goes through with nothing done: no selected person is changed.
            return new BulkGradeView(0, List.of());
        }
        Set<Long> selected = new HashSet<>(request.ids());
        List<PersonView> changed = new ArrayList<>();
        for (Person person : people.list(sheetId)) {
            if (selected.contains(person.getId())) {
                try {
                    Person edited = people.update(sheetId, user, person.getId(), person.getVersion(),
                            person.getName(), request.grade(), request.from(), person.getTerms());
                    changed.add(view(sheetId, edited));
                } catch (ResponseStatusException refusal) {
                    // Saved by someone else meanwhile: theirs stands, and this person is not changed.
                }
            }
        }
        return new BulkGradeView(changed.size(), changed);
    }

    /**
     * Retire the selected people together: each, in list order, is retired from the date given as the row's retire
     * would; one not on the sheet, or refused, is skipped.
     */
    @PostMapping("/bulk-retire")
    public BulkRetireView bulkRetire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkRetire request) {
        if (roles.isViewer(sheetId, user)) {
            // A viewer's bulk action goes through with nothing done: no selected person is retired.
            return new BulkRetireView(0, List.of());
        }
        Set<Long> selected = new HashSet<>(request.ids());
        List<PersonView> retired = new ArrayList<>();
        for (Person person : people.list(sheetId)) {
            if (selected.contains(person.getId())) {
                try {
                    retired.add(view(sheetId, people.retire(sheetId, user, person.getId(), request.from())));
                } catch (ResponseStatusException refusal) {
                    // Refused for this person: they are left as they are.
                }
            }
        }
        return new BulkRetireView(retired.size(), retired);
    }

    /** Swap the person with the one above or below them: everyone on the sheet, in list order, as moved. */
    @PostMapping("/{id}/move")
    public List<PersonView> move(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Move request) {
        List<Person> listed = people.move(sheetId, user, id, "up".equals(request.direction()));
        LocalDate today = people.today(sheetId);
        Set<String> used = people.usedInTree(sheetId);
        return listed.stream().map(p -> PersonView.of(p, today, PersonService.clashes(p, used))).toList();
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody Retirement request) {
        return view(sheetId, people.retire(sheetId, user, id, request.from()));
    }

    /** Give the person an alias. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AliasForm request) {
        return view(sheetId, people.addAlias(sheetId, user, id, request.alias()));
    }

    /** Merge the person into another of the sheet: the person merged into, as saved. */
    @PostMapping("/{id}/merge")
    public PersonView merge(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MergeForm request) {
        return view(sheetId, merges.merge(sheetId, user, id, request.intoId()));
    }

    /** Transfer the person to another sheet; they leave this one. */
    @PostMapping("/{id}/transfer")
    public void transfer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TransferForm request) {
        transfers.transfer(sheetId, user, id, request.toSheetId());
    }

    /** Put the person on hold over the dates given, alongside any holds they already have. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldForm request) {
        return view(sheetId, people.hold(sheetId, user, id, request.from(), request.to()));
    }

    /** The person as listed, with whether they clash on the sheet's tree. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, people.today(sheetId), PersonService.clashes(person, people.usedInTree(sheetId)));
    }
}
