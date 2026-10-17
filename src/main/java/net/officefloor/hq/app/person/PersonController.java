package net.officefloor.hq.app.person;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

/** People on a sheet. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /** The version is the one an edit sends back, so a save made from an out-of-date copy is refused. */
    public record PersonView(Long id, String name, String grade, long version, LocalDate retiredFrom) {
        static PersonView of(Person person) {
            return new PersonView(person.getId(), person.getName(), person.getGrade(), person.getVersion(),
                    person.getRetiredFrom());
        }
    }

    /** The name is stored trimmed; one that is empty or only spaces is refused. */
    public record PersonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    /** An edit carries the version of the person it was made from. */
    public record PersonUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade,
            @NotNull Long version) {
    }

    /** Retire a person from a date; a missing date is the sheet's today. */
    public record RetireRequest(LocalDate from) {
    }

    /** Lines of name,grade pasted into the import box. */
    public record ImportRequest(@NotNull @Size(max = 100_000) String text) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PersonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PersonRequest request) {
        return PersonView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.grade()));
    }

    @PutMapping("/{personId}")
    public PersonView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated PersonUpdateRequest request) {
        return PersonView.of(service.update(sheetId.trim(), user.trim(), personId, request.version(),
                request.name(), request.grade()));
    }

    @PostMapping("/{personId}/retire")
    public PersonView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody RetireRequest request) {
        return PersonView.of(service.retire(sheetId.trim(), user.trim(), personId, request.from()));
    }

    @PostMapping("/import")
    public PersonService.ImportResult importPeople(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated ImportRequest request) {
        return service.importPeople(sheetId.trim(), user.trim(), request.text());
    }
}
