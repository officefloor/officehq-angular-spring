package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/people")
public class PersonController {

    public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma,
            Integer thrust, Integer drift, Integer momentum, String tier, List<PatronView> patrons,
            Integer backing, Integer net) {
        static PersonView of(Person p) {
            return new PersonView(p.getLocalId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(),
                    p.getThrust(), p.getDrift(), p.getMomentum(), p.getTier(),
                    p.getPatrons().stream().map(o -> new PatronView(o.getLocalId(), o.getName())).toList(),
                    p.getBacking(), p.getNet());
        }
    }

    public record PatronView(Long id, String name) {
    }

    public record NewPerson(@NotBlank String name, Integer alpha, Integer beta, Integer gamma) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@RequestParam(required = false) String sheet) {
        return service.list(sheetId(sheet)).stream().map(PersonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@RequestParam(required = false) String sheet, @Valid @RequestBody NewPerson person) {
        return PersonView.of(service.add(sheetId(sheet), person.name(), person.alpha(), person.beta(), person.gamma()));
    }

    /**
     * Sets, on the person with this id on the request's sheet, whichever of alpha, beta, gamma and
     * patronIds (a list of ids on that sheet) the body names; a null value clears it.
     */
    @PatchMapping("/{id}")
    public PersonView update(@RequestParam(required = false) String sheet, @PathVariable long id,
            @RequestBody Map<String, Object> update) {
        String sheetId = sheetId(sheet);
        Optional<Person> person = service.find(sheetId, id);
        if (update.containsKey("alpha")) {
            person = service.setAlpha(sheetId, id, integer(update.get("alpha")));
        }
        if (update.containsKey("beta")) {
            person = service.setBeta(sheetId, id, integer(update.get("beta")));
        }
        if (update.containsKey("gamma")) {
            person = service.setGamma(sheetId, id, integer(update.get("gamma")));
        }
        if (update.containsKey("patronIds")) {
            List<Long> patronIds = new ArrayList<>();
            if (update.get("patronIds") instanceof List<?> ids) {
                for (Object patronId : ids) {
                    patronIds.add(integer(patronId).longValue());
                }
            } else if (update.get("patronIds") != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "patronIds must be a list");
            }
            try {
                person = service.setPatrons(sheetId, id, patronIds);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return person.map(PersonView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The sheet named by the request, trimmed; blank or absent means the unnamed sheet. */
    private static String sheetId(String sheet) {
        return sheet == null || sheet.isBlank() ? null : sheet.trim();
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected a whole number, got " + value);
    }
}
