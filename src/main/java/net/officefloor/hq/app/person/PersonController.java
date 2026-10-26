package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerPasses;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /**
     * A person as listed: the grade is the one in force on the sheet's today, the grades every one recorded, the
     * terms their own (null: none).
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            LocalDate retiredFrom, boolean held, List<HoldView> holds, Integer terms) {
        public static PersonView of(Person p, LocalDate today) {
            return new PersonView(p.getId(), p.getName(), p.gradeOn(today),
                    p.getGrades().stream().map(GradeView::of).toList(), p.getVersion(), p.getRetiredFrom(),
                    p.isHeld(), p.getHolds().stream().map(HoldView::of).toList(), p.getTerms());
        }
    }

    /** A hold on a person from its from-date to its to-date (null: with no end). */
    public record HoldView(LocalDate from, LocalDate to) {
        public static HoldView of(DatedHold h) {
            return new HoldView(h.getFrom(), h.getTo());
        }
    }

    /** A grade a person has from its from-date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
        public static GradeView of(DatedGrade g) {
            return new GradeView(g.getGrade(), g.getFrom());
        }
    }

    /**
     * A person's fields as the form submits them; the name is stored without surrounding spaces, the terms are whole
     * days, 0 or more, or null for none.
     */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @Min(0) @Max(99999) Integer terms) {
    }

    /**
     * An edit of a person: its fields, the date its grade applies from (missing: the sheet's today) and the version
     * of the person the editor loaded, and their own terms (null: none).
     */
    public record PersonEdit(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate gradeFrom, @NotNull Long version,
            @Min(0) @Max(99999) Integer terms) {
    }

    /** One person named as another's patron. */
    public record PatronView(Long id, Long personId, Long patronId) {
        public static PatronView of(Patron p) {
            return new PatronView(p.getId(), p.getPersonId(), p.getPatronId());
        }
    }

    /** A patron to add: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** A retirement: the date it takes effect; a missing date takes the sheet's today. */
    public record RetireForm(LocalDate from) {
    }

    /** A hold: the date it starts (missing: the sheet's today) and the date it ends (missing: with no end). */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A grade to give the people with the ids together, from its from-date (missing: the sheet's today). */
    public record BulkGradeForm(@NotEmpty Set<Long> ids, @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** The people a bulk grade changed, in list order. */
    public record BulkGradeView(List<PersonView> graded) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchForm(@NotNull Boolean on) {
    }

    /** Whether the signed-in user watches the person now. */
    public record WatchView(Long personId, boolean watched) {
    }

    private final PersonService service;
    private final WatchService watches;
    private final SettingsService settings;

    public PersonController(PersonService service, WatchService watches, SettingsService settings) {
        this.service = service;
        this.watches = watches;
        this.settings = settings;
    }

    /** All the people on the sheet, in the order they were added. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        LocalDate today = service.today(sheetId);
        return service.list(sheetId).stream().map(p -> PersonView.of(p, today)).toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PersonForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.add(user, sheetId, form.name(), form.grade(), form.terms()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonEdit form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId,
                service.update(user, sheetId, id, form.version(), form.name(), form.grade(), form.gradeFrom(),
                        form.terms()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody RetireForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.retire(user, sheetId, id, form.from()));
    }

    /** Puts a hold on the person, from its from-date to its to-date; the holds already put are kept. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody HoldForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.hold(user, sheetId, id, form.from(), form.to()));
    }

    /**
     * Gives the people the grade from the from-date together, in list order, as each one's edit would. A viewer, or
     * anyone on an archived sheet, goes through with none changed.
     */
    @PostMapping("/bulk-grade")
    @ViewerPasses
    public BulkGradeView bulkGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkGradeForm form,
            @SessionAttribute(SessionController.USER) String user) {
        if (settings.isReadOnly(sheetId, user)) {
            return new BulkGradeView(List.of());
        }
        List<Person> graded = service.bulkGrade(user, sheetId, form.ids(), form.grade(), form.from());
        LocalDate today = service.today(sheetId);
        return new BulkGradeView(graded.stream().map(p -> PersonView.of(p, today)).toList());
    }

    /** The person as listed on the sheet's today. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId));
    }

    /** The ids of the people on the sheet the signed-in user watches, in the order they started watching. */
    @GetMapping("/watched")
    public List<Long> watched(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @SessionAttribute(SessionController.USER) String user) {
        return watches.watched(sheetId, user);
    }

    /** Starts or stops the signed-in user watching the person. */
    @PostMapping("/{id}/watch")
    public WatchView watch(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody WatchForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return new WatchView(id, watches.watch(user, sheetId, id, form.on()));
    }

    /** Every patron named on the sheet, in the order they were named. */
    @GetMapping("/patrons")
    public List<PatronView> patrons(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.patrons(sheetId).stream().map(PatronView::of).toList();
    }

    /** Names another person on the sheet as the person's patron; refused for themselves or one already named. */
    @PostMapping("/{id}/patrons")
    public PatronView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PatronForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return PatronView.of(service.addPatron(user, sheetId, id, form.patronId()));
    }
}
