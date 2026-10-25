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
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** A grade the person was given, from its from-date (null: from the beginning of time). */
    public record GradeView(Grade grade, LocalDate from) {
    }

    /** A hold put on the person, from its from-date (null: the beginning of time) to its to-date (null: no end). */
    public record HoldView(LocalDate from, LocalDate to) {
    }

    /** What the screen shows of a person: grade is the one in force on the sheet's today, grades every
     *  grade they were given, the one from the beginning of time first, then by from-date; held whether
     *  they have any hold, holds every hold put on them, by from-date; watched whether the signed-in
     *  user watches them; clashing whether their name or an alias is used on another sheet of the
     *  sheet's tree. */
    public record PersonView(long id, String name, Grade grade, List<GradeView> grades, long version,
            boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds,
            List<Long> patronIds, boolean watched, Integer terms, String contact, List<String> aliases, boolean clashing) {
        /** A person with no patrons, with the grade in force on the sheet's today. */
        public static PersonView of(Person person, LocalDate today, boolean watched, boolean clashing) {
            return of(person, List.of(), today, watched, clashing);
        }

        /** A person with the ids of their patrons, in the order they were named, the grade in force
         *  on the sheet's today, and whether the signed-in user watches them. */
        public static PersonView of(Person person, List<Long> patronIds, LocalDate today, boolean watched,
                boolean clashing) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(g -> new GradeView(g.getGrade(), g.getFrom())).toList(),
                    person.getVersion(), person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(h -> new HoldView(h.getFrom(), h.getTo())).toList(), patronIds,
                    watched, person.getTerms(), person.getContact(), person.getAliases(), clashing);
        }
    }

    /** A person's own terms as entered: whole days, blank for none. */
    static final String TERMS = "\\s*\\d{0,5}\\s*";

    /** A person's fields, as entered on the form; a new person's grade applies from the beginning of time,
     *  their terms are whole days, blank for none; a blank contact is none. */
    public record PersonForm(@NotBlank @Size(max = 200) String name, @NotNull Grade grade,
            @Pattern(regexp = TERMS) String terms, @Size(max = 200) String contact) {
    }

    /** A person's fields as edited, with the version of the person the editor loaded; the grade is
     *  given from the from-date, none meaning the sheet's today. */
    public record PersonEdit(@NotBlank @Size(max = 200) String name, @NotNull Grade grade, LocalDate gradeFrom,
            @Pattern(regexp = TERMS) String terms, @Size(max = 200) String contact, @NotNull Long version) {
    }

    /** The person named as a patron. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** Another name the person is to be found by. */
    public record AliasForm(@Size(max = 200) String alias) {
    }

    /** The person another is to be merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** The id of the sheet a person is to be transferred to. */
    public record TransferForm(@NotBlank @Size(max = 100) String to) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchForm(@NotNull Boolean on) {
    }

    /** A retirement; no date means the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A hold; no from-date means the sheet's today, no to-date no end. */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** The people selected to be given a grade together, from the from-date (none: the sheet's today). */
    public record BulkGradeForm(@NotNull List<@NotNull Long> ids, @NotNull Grade grade, LocalDate from) {
    }

    /** What a bulk grade did: the people it changed, and how many. */
    public record BulkGradeView(List<PersonView> people, int changed) {
    }

    /** The import box's text: lines of name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many people the import added and how many it updated, and the people, in the order of their lines. */
    public record ImportView(int added, int updated, List<PersonView> people) {
    }

    private final PersonService service;
    private final ImportPeopleService imports;
    private final BulkGradeService bulkGrade;
    private final MergeService merges;
    private final TransferService transfers;
    private final SheetService sheets;

    public PersonController(PersonService service, BulkGradeService bulkGrade, MergeService merges,
            TransferService transfers, SheetService sheets, ImportPeopleService imports) {
        this.service = service;
        this.imports = imports;
        this.bulkGrade = bulkGrade;
        this.merges = merges;
        this.transfers = transfers;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(name = SignedInUser.HEADER, required = false) String user) {
        String sheet = sheetId.trim();
        Map<Long, List<Long>> patronIds = service.patronIds(sheet);
        Set<Long> watched = user == null || user.isBlank() ? Set.of()
                : service.watchedIds(sheet, SignedInUser.name(user));
        LocalDate today = sheets.today(sheet);
        Set<Long> clashing = service.clashingIds(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, patronIds.getOrDefault(p.getId(), List.of()), today,
                        watched.contains(p.getId()), clashing.contains(p.getId())))
                .toList();
    }

    /** Add a person. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonForm form) {
        Person added = service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.grade(),
                termsOf(form.terms()), form.contact());
        return PersonView.of(added, sheets.today(added.getSheetId()), false, service.isClashing(added));
    }

    /** Add or update a person per pasted line, all or none. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportView importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ImportForm form) {
        ImportPeopleService.Result result = imports.importPeople(sheetId.trim(), SignedInUser.name(user), form.text());
        List<PersonView> people = result.people().stream().map(p -> view(p, user)).toList();
        return new ImportView(result.added(), result.updated(), people);
    }

    /** Edit a person. */
    @PutMapping("/{id}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PersonEdit form) {
        return view(service.update(sheetId.trim(), SignedInUser.name(user), id, form.version(),
                form.name(), form.grade(), form.gradeFrom(), termsOf(form.terms()), form.contact()), user);
    }

    /** Give the selected people a grade together, each in list order as the person form would. */
    @PostMapping("/grade")
    public BulkGradeView grade(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid BulkGradeForm form) {
        List<Person> changed = bulkGrade.grade(sheetId.trim(), SignedInUser.name(user), form.ids(), form.grade(),
                form.from());
        return new BulkGradeView(changed.stream().map(p -> view(p, user)).toList(), changed.size());
    }

    /** Retire a person from a date. */
    @PostMapping("/{id}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody RetireForm form) {
        return view(service.retire(sheetId.trim(), SignedInUser.name(user), id, form.from()), user);
    }

    /** Put a hold on a person. */
    @PostMapping("/{id}/hold")
    public PersonView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody HoldForm form) {
        return view(service.hold(sheetId.trim(), SignedInUser.name(user), id, form.from(), form.to()), user);
    }

    /** Name another person on the sheet as one of the person's patrons. */
    @PostMapping("/{id}/patrons")
    public PersonView addPatron(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PatronForm form) {
        return view(service.addPatron(sheetId.trim(), SignedInUser.name(user), id, form.patronId()), user);
    }

    /** Give a person another name they are found by. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid AliasForm form) {
        return view(service.addAlias(sheetId.trim(), SignedInUser.name(user), id, form.alias()), user);
    }

    /** Merge a person into another; the person merged into is answered. */
    @PostMapping("/{id}/merge")
    public PersonView merge(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid MergeForm form) {
        return view(merges.merge(sheetId.trim(), SignedInUser.name(user), id, form.intoId()), user);
    }

    /** Transfer a person to another sheet; they leave this one. */
    @PostMapping("/{id}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TransferForm form) {
        transfers.transfer(sheetId.trim(), SignedInUser.name(user), id, form.to().trim());
    }

    /** Start or stop the signed-in user watching a person. */
    @PutMapping("/{id}/watch")
    public PersonView watch(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid WatchForm form) {
        return view(service.watch(sheetId.trim(), SignedInUser.name(user), id, form.on()), user);
    }

    /** The terms as entered, in days; null when left blank. */
    private static Integer termsOf(String terms) {
        return terms == null || terms.isBlank() ? null : Integer.valueOf(terms.trim());
    }

    private PersonView view(Person person, String user) {
        return PersonView.of(person, service.patronIdsOf(person.getId()), sheets.today(person.getSheetId()),
                service.isWatched(person.getId(), SignedInUser.name(user)), service.isClashing(person));
    }
}
