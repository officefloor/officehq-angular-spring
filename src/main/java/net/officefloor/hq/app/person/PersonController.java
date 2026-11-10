package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.claim.ClaimController;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding and editing the people on a sheet. */
@RestController
@RequestMapping("/api/people")
public class PersonController {

    public record ListPeopleRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddPersonRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = "[ABC]") String grade, @Min(0) Integer terms,
            @Size(max = 200) String contact, @Size(max = 500) String tags) {
    }

    public record UpdatePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate gradeFrom, @NotNull Long version,
            @Min(0) Integer terms, @Size(max = 200) String contact, @Size(max = 500) String tags) {
    }

    public record AddAliasRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotBlank @Size(max = 200) String alias) {
    }

    public record MergePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Long intoId) {
    }

    public record ImportPeopleRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull @Size(max = 100000) String text) {
    }

    /**
     * What an import did: how many people it added and how many it updated, and each of them as the
     * store now holds them, the added then the updated, each in line order.
     */
    public record ImportPeopleResponse(int added, int updated, List<PersonResponse> people) {
    }

    public record TransferPersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotBlank @Size(max = 100) String to) {
    }

    public record BulkGradeRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotEmpty List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate from) {
    }

    /** How many people were given the grade, and each as the store now holds them. */
    public record BulkGradeResponse(int graded, List<PersonResponse> people) {
    }

    public record BulkRetireRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotEmpty List<@NotNull Long> ids, LocalDate date) {
    }

    /** How many people were retired, and each as the store now holds them. */
    public record BulkRetireResponse(int retired, List<PersonResponse> people) {
    }

    public record RetirePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, LocalDate date) {
    }

    public record UnretirePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id) {
    }

    public record MovePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotNull @Pattern(regexp = "up|down") String direction) {
    }

    public record HoldPersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, LocalDate from, LocalDate to) {
    }

    public record AddPatronRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Long patronId) {
    }

    public record ListWatchedRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user) {
    }

    public record WatchPersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Boolean watch) {
    }

    public record PatronResponse(long personId, long patronId) {
    }

    /** One of a person's grades, from a from-date (null: from the beginning of time). */
    public record GradeResponse(LocalDate from, String grade) {
    }

    /** One of a person's holds, from a from-date to a to-date (null: open-ended). */
    public record HoldResponse(LocalDate from, LocalDate to) {
    }

    /** One of a person's past retirements, from its from-date to the day they were unretired. */
    public record RetirementResponse(LocalDate from, LocalDate to) {
    }

    /** A person, with every grade recorded for them, the one from the beginning of time first, their own terms or null, their contact or null, their aliases sorted as names are compared, their tags sorted, and whether they clash: their name or an alias is used on another sheet of the sheet's tree, and the sheet they were transferred from or null. */
    public record PersonResponse(long id, String name, List<GradeResponse> grades, long version, boolean retired,
            LocalDate retiredFrom, boolean held, List<HoldResponse> holds, Integer terms, String contact,
            List<String> aliases, boolean clashing, String origin, List<RetirementResponse> retirements, List<String> tags) {
    }

    public static PersonResponse response(Person person, boolean clashing) {
        List<GradeResponse> grades = person.getGrades().stream()
                .map(g -> new GradeResponse(g.getFromDate(), g.getGrade())).toList();
        return new PersonResponse(person.getId(), person.getName(), grades, person.getVersion(),
                person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                person.getHolds().stream().map(h -> new HoldResponse(h.getFromDate(), h.getToDate())).toList(),
                person.getTerms(), person.getContact(), person.getAliases(), clashing, person.getOriginSheet(),
                person.getPastRetirements().stream().map(r -> new RetirementResponse(r.getFromDate(), r.getToDate())).toList(),
                person.getTags());
    }

    /** The person, with whether they clash on the sheet's tree. */
    private PersonResponse response(String sheetId, Person person) {
        return response(person, service.clashing(sheetId, List.of(person)).contains(person.getId()));
    }

    /** A contact as stored: trimmed and lower-cased; null when blank. */
    private static String contact(String contact) {
        String c = contact == null ? "" : contact.trim().toLowerCase(Locale.ROOT);
        return c.isEmpty() ? null : c;
    }

    private final PersonService service;
    private final PersonMergeService merges;
    private final PersonTransferService transfers;
    private final PersonImportService imports;

    public PersonController(PersonService service, PersonMergeService merges, PersonTransferService transfers,
            PersonImportService imports) {
        this.imports = imports;
        this.service = service;
        this.merges = merges;
        this.transfers = transfers;
    }

    @PostMapping("/list")
    public List<PersonResponse> list(@Valid @RequestBody ListPeopleRequest req) {
        String sheet = req.sheet().trim();
        List<Person> people = service.list(sheet);
        Set<Long> clashing = service.clashing(sheet, people);
        return people.stream().map(p -> response(p, clashing.contains(p.getId()))).toList();
    }

    @PostMapping("/add")
    public PersonResponse add(@Valid @RequestBody AddPersonRequest req) {
        return response(req.sheet().trim(), service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.grade(),
                req.terms(), contact(req.contact()), ClaimController.tags(req.tags())));
    }

    @PostMapping("/import")
    public ImportPeopleResponse importPeople(@Valid @RequestBody ImportPeopleRequest req) {
        String sheet = req.sheet().trim();
        PersonImportService.Result done = imports.importPeople(sheet, req.user().trim(), req.text());
        List<Person> touched = Stream.concat(done.added().stream(), done.updated().stream()).toList();
        Set<Long> clashing = service.clashing(sheet, touched);
        return new ImportPeopleResponse(done.added().size(), done.updated().size(),
                touched.stream().map(p -> response(p, clashing.contains(p.getId()))).toList());
    }

    @PostMapping("/update")
    public PersonResponse update(@Valid @RequestBody UpdatePersonRequest req) {
        return response(req.sheet().trim(), service.update(req.sheet().trim(), req.user().trim(), req.id(), req.version(), req.name().trim(),
                req.grade(), req.gradeFrom(), req.terms(), contact(req.contact()), ClaimController.tags(req.tags())));
    }

    @PostMapping("/aliases/add")
    public PersonResponse addAlias(@Valid @RequestBody AddAliasRequest req) {
        return response(req.sheet().trim(), service.addAlias(req.sheet().trim(), req.user().trim(), req.id(), req.alias().trim()));
    }

    /** Merge the person into another; answers the other as the store now holds them. */
    @PostMapping("/merge")
    public PersonResponse merge(@Valid @RequestBody MergePersonRequest req) {
        return response(req.sheet().trim(), merges.merge(req.sheet().trim(), req.user().trim(), req.id(), req.intoId()));
    }

    /** Transfer the person to another sheet by id; answers them as the other sheet now holds them. */
    @PostMapping("/transfer")
    public PersonResponse transfer(@Valid @RequestBody TransferPersonRequest req) {
        return response(req.to().trim(), transfers.transfer(req.sheet().trim(), req.user().trim(), req.id(), req.to().trim()));
    }

    @PostMapping("/bulk-grade")
    public BulkGradeResponse bulkGrade(@Valid @RequestBody BulkGradeRequest req) {
        List<PersonResponse> graded = service.bulkGrade(req.sheet().trim(), req.user().trim(), req.ids(), req.grade(),
                req.from()).stream().map(p -> response(req.sheet().trim(), p)).toList();
        return new BulkGradeResponse(graded.size(), graded);
    }

    @PostMapping("/bulk-retire")
    public BulkRetireResponse bulkRetire(@Valid @RequestBody BulkRetireRequest req) {
        List<PersonResponse> retired = service.bulkRetire(req.sheet().trim(), req.user().trim(), req.ids(),
                req.date()).stream().map(p -> response(req.sheet().trim(), p)).toList();
        return new BulkRetireResponse(retired.size(), retired);
    }

    @PostMapping("/retire")
    public PersonResponse retire(@Valid @RequestBody RetirePersonRequest req) {
        return response(req.sheet().trim(), service.retire(req.sheet().trim(), req.user().trim(), req.id(), req.date()));
    }

    @PostMapping("/unretire")
    public PersonResponse unretire(@Valid @RequestBody UnretirePersonRequest req) {
        return response(req.sheet().trim(), service.unretire(req.sheet().trim(), req.user().trim(), req.id()));
    }

    /** Swap the person with the one above or below them in the list. */
    @PostMapping("/move")
    public PersonResponse move(@Valid @RequestBody MovePersonRequest req) {
        return response(req.sheet().trim(), service.move(req.sheet().trim(), req.user().trim(), req.id(),
                req.direction().equals("up")));
    }

    @PostMapping("/hold")
    public PersonResponse hold(@Valid @RequestBody HoldPersonRequest req) {
        return response(req.sheet().trim(), service.hold(req.sheet().trim(), req.user().trim(), req.id(), req.from(), req.to()));
    }

    @PostMapping("/patrons/list")
    public List<PatronResponse> patrons(@Valid @RequestBody ListPeopleRequest req) {
        return service.patrons(req.sheet().trim()).stream()
                .map(p -> new PatronResponse(p.getPersonId(), p.getPatronId())).toList();
    }

    @PostMapping("/patrons/add")
    public PatronResponse addPatron(@Valid @RequestBody AddPatronRequest req) {
        Patron added = service.addPatron(req.sheet().trim(), req.user().trim(), req.id(), req.patronId());
        return new PatronResponse(added.getPersonId(), added.getPatronId());
    }

    /** The ids of the people the user watches on the sheet. */
    @PostMapping("/watched")
    public List<Long> watched(@Valid @RequestBody ListWatchedRequest req) {
        return service.watched(req.sheet().trim(), req.user().trim());
    }

    @PostMapping("/watch")
    public void watch(@Valid @RequestBody WatchPersonRequest req) {
        service.watch(req.sheet().trim(), req.user().trim(), req.id(), req.watch());
    }
}
