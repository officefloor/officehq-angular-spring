package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            Grade grade, Integer weight, Integer thrust, Integer drift, Integer momentum, Integer load, String tier, List<PatronView> patrons,
            Integer backing, Integer net, Integer rank, Integer percentile, LocalDate onset, Long age, Long decay, boolean active, Integer effective,
            Integer clout, Integer influence, List<String> regions, Integer reach,
            List<PatronView> allies, int allyCount, Integer synergy) {
        static PersonView of(Person p, Map<Long, Integer> ranks, LocalDate anchor) {
            Integer rank = ranks.get(p.getLocalId());
            return new PersonView(p.getLocalId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(),
                    p.getGrade(), p.getWeight(), p.getThrust(), p.getDrift(), p.getMomentum(), p.getLoad(), p.getTier(),
                    p.getPatrons().stream().map(o -> new PatronView(o.getLocalId(), o.getName())).toList(),
                    p.getBacking(), p.getNet(), rank, PersonController.percentile(rank, ranks.size()), p.getOnset(),
                    p.getAge(anchor), p.getDecay(anchor), p.isActive(), p.getEffective(), p.getClout(),
                    p.getInfluence(), p.getRegions(), p.getReach(),
                    p.getAllies().stream().map(o -> new PatronView(o.getLocalId(), o.getName())).toList(),
                    p.getAllies().size(), p.getSynergy());
        }
    }

    /**
     * A rank as a percentile of the ranked people: the best 100, the worst 0, evenly spread between
     * by rank and rounded to a whole number. A lone ranked person is 100; unranked has none.
     */
    static Integer percentile(Integer rank, int ranked) {
        if (rank == null) {
            return null;
        }
        if (ranked <= 1) {
            return 100;
        }
        return (int) Math.round((ranked - rank) * 100.0 / (ranked - 1));
    }

    /** Highest momentum first; equal momentum by higher alpha, then by name, earlier in the alphabet first. */
    private static final Comparator<Person> RANK_ORDER = Comparator
            .comparing(Person::getMomentum, Comparator.reverseOrder())
            .thenComparing(Person::getAlpha, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Person::getName)
            .thenComparing(Person::getLocalId);

    public record PatronView(Long id, String name) {
    }

    public record NewPerson(@NotBlank String name, Integer alpha, Integer beta, Integer gamma, Grade grade, List<String> regions) {
    }

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonView> list(@RequestParam(required = false) String sheet) {
        String sheetId = sheetId(sheet);
        List<Person> people = service.list(sheetId);
        Map<Long, Integer> ranks = ranks(people);
        LocalDate anchor = service.anchor(sheetId);
        return people.stream().map(p -> PersonView.of(p, ranks, anchor)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@RequestParam(required = false) String sheet, @Valid @RequestBody NewPerson person) {
        return view(sheetId(sheet), service.add(sheetId(sheet), person.name(), person.alpha(), person.beta(), person.gamma(),
                person.grade(), regions(person.regions())));
    }

    /**
     * Sets, on the person with this id on the request's sheet, whichever of alpha, beta, gamma, grade, regions (a list of codes), active
     * (true or false), patronIds and allyIds (lists of ids on that sheet) the body names; a null value clears it.
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
        if (update.containsKey("grade")) {
            person = service.setGrade(sheetId, id, grade(update.get("grade")));
        }
        if (update.containsKey("regions")) {
            List<String> regions = new ArrayList<>();
            if (update.get("regions") instanceof List<?> values) {
                for (Object region : values) {
                    if (!(region instanceof String text)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "regions must be text");
                    }
                    regions.add(text);
                }
            } else if (update.get("regions") != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "regions must be a list");
            }
            person = service.setRegions(sheetId, id, regions(regions));
        }
        if (update.containsKey("active")) {
            if (!(update.get("active") instanceof Boolean active)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active must be true or false");
            }
            person = service.setActive(sheetId, id, active);
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
        if (update.containsKey("allyIds")) {
            List<Long> allyIds = new ArrayList<>();
            if (update.get("allyIds") instanceof List<?> ids) {
                for (Object allyId : ids) {
                    allyIds.add(integer(allyId).longValue());
                }
            } else if (update.get("allyIds") != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allyIds must be a list");
            }
            try {
                person = service.setAllies(sheetId, id, allyIds);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return person.map(p -> view(sheetId, p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** A person's view, ranked among the people on their sheet. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, ranks(service.list(sheetId)), service.anchor(sheetId));
    }

    /**
     * Each person's rank by local id: 1 for the highest momentum, then 2, 3, ... with equal momentum
     * ordered by higher alpha, then by name. People without a momentum are not ranked.
     */
    private static Map<Long, Integer> ranks(List<Person> people) {
        List<Person> ranked = people.stream().filter(p -> p.getMomentum() != null).sorted(RANK_ORDER).toList();
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            ranks.put(ranked.get(i).getLocalId(), i + 1);
        }
        return ranks;
    }

    /** The sheet named by the request, trimmed; blank or absent means the unnamed sheet. */
    private static String sheetId(String sheet) {
        return sheet == null || sheet.isBlank() ? null : sheet.trim();
    }

    /** Regions as typed, each trimmed, leaving out blanks and repeats; none when absent. */
    static Set<String> regions(List<String> values) {
        Set<String> regions = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String region = region(value);
                if (region != null) {
                    regions.add(region);
                }
            }
        }
        return regions;
    }

    /** A region as typed, trimmed; blank means none. Refuses one longer than {@link Person#REGION_MAX_LENGTH}. */
    static String region(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String region = value.trim();
        if (region.length() > Person.REGION_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "region must be at most " + Person.REGION_MAX_LENGTH + " characters");
        }
        return region;
    }

    private static Grade grade(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Grade.parse(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
