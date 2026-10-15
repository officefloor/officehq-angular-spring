package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/people")
public class PersonController {

    public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma,
            Integer thrust, Integer drift, Integer momentum, String tier, Long patronId,
            String patronName, Integer backing, Integer net) {
        static PersonView of(Person p) {
            Person patron = p.getPatron();
            return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(),
                    p.getThrust(), p.getDrift(), p.getMomentum(), p.getTier(),
                    patron == null ? null : patron.getId(), patron == null ? null : patron.getName(),
                    p.getBacking(), p.getNet());
        }
    }

    public record NewPerson(@NotBlank String name, Integer alpha, Integer beta, Integer gamma) {
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
        return PersonView.of(service.add(person.name(), person.alpha(), person.beta(), person.gamma()));
    }

    /** Sets whichever of alpha, beta, gamma and patronId the body names; a null value clears it. */
    @PatchMapping("/{id}")
    public PersonView update(@PathVariable long id, @RequestBody Map<String, Integer> update) {
        Optional<Person> person = service.find(id);
        if (update.containsKey("alpha")) {
            person = service.setAlpha(id, update.get("alpha"));
        }
        if (update.containsKey("beta")) {
            person = service.setBeta(id, update.get("beta"));
        }
        if (update.containsKey("gamma")) {
            person = service.setGamma(id, update.get("gamma"));
        }
        if (update.containsKey("patronId")) {
            Integer patronId = update.get("patronId");
            try {
                person = service.setPatron(id, patronId == null ? null : patronId.longValue());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return person.map(PersonView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
