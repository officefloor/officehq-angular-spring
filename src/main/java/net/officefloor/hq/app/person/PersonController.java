package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
            @Size(max = 200) String contact) {
    }

    public record UpdatePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate gradeFrom, @NotNull Long version,
            @Min(0) Integer terms, @Size(max = 200) String contact) {
    }

    public record BulkGradeRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotEmpty List<@NotNull Long> ids,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate from) {
    }

    /** How many people were given the grade, and each as the store now holds them. */
    public record BulkGradeResponse(int graded, List<PersonResponse> people) {
    }

    public record RetirePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, LocalDate date) {
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

    /** A person, with every grade recorded for them, the one from the beginning of time first, their own terms or null, and their contact or null. */
    public record PersonResponse(long id, String name, List<GradeResponse> grades, long version, boolean retired,
            LocalDate retiredFrom, boolean held, List<HoldResponse> holds, Integer terms, String contact) {
    }

    public static PersonResponse response(Person person) {
        List<GradeResponse> grades = person.getGrades().stream()
                .map(g -> new GradeResponse(g.getFromDate(), g.getGrade())).toList();
        return new PersonResponse(person.getId(), person.getName(), grades, person.getVersion(),
                person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                person.getHolds().stream().map(h -> new HoldResponse(h.getFromDate(), h.getToDate())).toList(),
                person.getTerms(), person.getContact());
    }

    /** A contact as stored: trimmed and lower-cased; null when blank. */
    private static String contact(String contact) {
        String c = contact == null ? "" : contact.trim().toLowerCase(Locale.ROOT);
        return c.isEmpty() ? null : c;
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PersonResponse> list(@Valid @RequestBody ListPeopleRequest req) {
        return service.list(req.sheet().trim()).stream().map(PersonController::response).toList();
    }

    @PostMapping("/add")
    public PersonResponse add(@Valid @RequestBody AddPersonRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.grade(),
                req.terms(), contact(req.contact())));
    }

    @PostMapping("/update")
    public PersonResponse update(@Valid @RequestBody UpdatePersonRequest req) {
        return response(service.update(req.sheet().trim(), req.user().trim(), req.id(), req.version(), req.name().trim(),
                req.grade(), req.gradeFrom(), req.terms(), contact(req.contact())));
    }

    @PostMapping("/bulk-grade")
    public BulkGradeResponse bulkGrade(@Valid @RequestBody BulkGradeRequest req) {
        List<PersonResponse> graded = service.bulkGrade(req.sheet().trim(), req.user().trim(), req.ids(), req.grade(),
                req.from()).stream().map(PersonController::response).toList();
        return new BulkGradeResponse(graded.size(), graded);
    }

    @PostMapping("/retire")
    public PersonResponse retire(@Valid @RequestBody RetirePersonRequest req) {
        return response(service.retire(req.sheet().trim(), req.user().trim(), req.id(), req.date()));
    }

    @PostMapping("/hold")
    public PersonResponse hold(@Valid @RequestBody HoldPersonRequest req) {
        return response(service.hold(req.sheet().trim(), req.user().trim(), req.id(), req.from(), req.to()));
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
