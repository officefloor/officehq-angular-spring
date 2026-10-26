package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
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

    /** A person as listed: the grade is the one in force on the sheet's today, the grades every one recorded. */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            LocalDate retiredFrom, boolean held) {
        public static PersonView of(Person p, LocalDate today) {
            return new PersonView(p.getId(), p.getName(), p.gradeOn(today),
                    p.getGrades().stream().map(GradeView::of).toList(), p.getVersion(), p.getRetiredFrom(),
                    p.isHeld());
        }
    }

    /** A grade a person has from its from-date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
        public static GradeView of(DatedGrade g) {
            return new GradeView(g.getGrade(), g.getFrom());
        }
    }

    /** A person's fields as the form submits them; the name is stored without surrounding spaces. */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /**
     * An edit of a person: its fields, the date its grade applies from (missing: the sheet's today) and the version
     * of the person the editor loaded.
     */
    public record PersonEdit(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate gradeFrom, @NotNull Long version) {
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

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
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
        return view(sheetId, service.add(user, sheetId, form.name(), form.grade()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonEdit form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId,
                service.update(user, sheetId, id, form.version(), form.name(), form.grade(), form.gradeFrom()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody RetireForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.retire(user, sheetId, id, form.from()));
    }

    /** Puts the person on hold, or takes them off hold when they are held. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.toggleHold(user, sheetId, id));
    }

    /** The person as listed on the sheet's today. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId));
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
