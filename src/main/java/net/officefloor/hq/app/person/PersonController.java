package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
            @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    public record UpdatePersonRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @NotNull Long version) {
    }

    public record PersonResponse(long id, String name, String grade, long version) {
    }

    private static PersonResponse response(Person person) {
        return new PersonResponse(person.getId(), person.getName(), person.getGrade(), person.getVersion());
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
        return response(service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.grade()));
    }

    @PostMapping("/update")
    public PersonResponse update(@Valid @RequestBody UpdatePersonRequest req) {
        return response(service.update(req.sheet().trim(), req.user().trim(), req.id(), req.version(), req.name().trim(),
                req.grade()));
    }
}
