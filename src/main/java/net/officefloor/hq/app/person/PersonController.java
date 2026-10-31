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
     *  recorded, in the order they take effect. */
    public record PersonView(long id, String name, String grade, List<GradeView> grades, Integer terms,
            long version, boolean retired, LocalDate retiredFrom, boolean held, List<HoldView> holds) {

        public static PersonView of(Person person, LocalDate today) {
            return new PersonView(person.getId(), person.getName(), person.gradeOn(today),
                    person.getGrades().stream().map(GradeView::of).toList(), person.getTerms(), person.getVersion(),
                    person.isRetired(), person.getRetiredFrom(), person.isHeld(),
                    person.getHolds().stream().map(HoldView::of).toList());
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
     *  terms: none of their own. */
    public record PersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @Min(0) @Max(36500) Integer terms) {
    }

    /** An edit of a person: their new values, the date the grade is from (missing: the sheet's today), their own
     *  terms (missing: none) and the version of the person the editor loaded. */
    public record PersonEdit(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate gradeFrom,
            @Min(0) @Max(36500) Integer terms,
            @NotNull Long version) {
    }

    /** A retirement: the date the person is retired from; missing takes the sheet's today. */
    public record Retirement(LocalDate from) {
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

    private final PersonService people;
    private final SheetHistory history;

    public PersonController(PersonService people, SheetHistory history) {
        this.people = people;
        this.history = history;
    }

    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.peopleAsOf(sheetId, asOf);
        }
        LocalDate today = people.today(sheetId);
        return people.list(sheetId).stream().map(p -> PersonView.of(p, today)).toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonForm request) {
        Person added = people.add(sheetId, user, request.name(), request.grade(), request.terms());
        return PersonView.of(added, people.today(sheetId));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PersonEdit request) {
        Person edited = people.update(sheetId, user, id, request.version(), request.name(), request.grade(),
                request.gradeFrom(), request.terms());
        return PersonView.of(edited, people.today(sheetId));
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
        Set<Long> selected = new HashSet<>(request.ids());
        List<PersonView> changed = new ArrayList<>();
        for (Person person : people.list(sheetId)) {
            if (selected.contains(person.getId())) {
                try {
                    Person edited = people.update(sheetId, user, person.getId(), person.getVersion(),
                            person.getName(), request.grade(), request.from(), person.getTerms());
                    changed.add(PersonView.of(edited, people.today(sheetId)));
                } catch (ResponseStatusException refusal) {
                    // Saved by someone else meanwhile: theirs stands, and this person is not changed.
                }
            }
        }
        return new BulkGradeView(changed.size(), changed);
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody Retirement request) {
        return PersonView.of(people.retire(sheetId, user, id, request.from()), people.today(sheetId));
    }

    /** Put the person on hold over the dates given, alongside any holds they already have. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody HoldForm request) {
        return PersonView.of(people.hold(sheetId, user, id, request.from(), request.to()), people.today(sheetId));
    }
}
