package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/people")
public class PersonController {

    public record PersonView(Long id, String name, Integer alpha) {
        static PersonView of(Person p) {
            return new PersonView(p.getId(), p.getName(), p.getAlpha());
        }
    }

    public record NewPerson(@NotBlank String name, @NotNull Integer alpha) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list() {
        return service.list().stream().map(PersonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@Valid @RequestBody NewPerson person) {
        return PersonView.of(service.add(person.name(), person.alpha()));
    }
}
