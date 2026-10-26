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

    public record PersonView(Long id, String name, String grade, long version, LocalDate retiredFrom,
            boolean held) {
        public static PersonView of(Person p) {
            return new PersonView(p.getId(), p.getName(), p.getGrade(), p.getVersion(), p.getRetiredFrom(),
                    p.isHeld());
        }
    }

    /** A person's fields as the form submits them; the name is stored without surrounding spaces. */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** An edit of a person: its fields and the version of the person the editor loaded. */
    public record PersonEdit(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @NotNull Long version) {
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
        return service.list(sheetId).stream().map(PersonView::of).toList();
    }

    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PersonForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return PersonView.of(service.add(user, sheetId, form.name(), form.grade()));
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonEdit form,
            @SessionAttribute(SessionController.USER) String user) {
        return PersonView.of(service.update(user, sheetId, id, form.version(), form.name(), form.grade()));
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody RetireForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return PersonView.of(service.retire(user, sheetId, id, form.from()));
    }

    /** Puts the person on hold, or takes them off hold when they are held. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return PersonView.of(service.toggleHold(user, sheetId, id));
    }
}
