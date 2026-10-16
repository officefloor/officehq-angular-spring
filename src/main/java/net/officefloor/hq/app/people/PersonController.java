package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, Integer delta, Integer epsilon, Integer zeta,
            Grade grade, Integer weight, Kind kind, Integer verge, Integer thrust, Integer drift, Integer momentum, Integer force, Integer sway, Integer torque, Integer pivot, Integer blend, Integer flux, Integer crest, Integer swing, Integer haul, Integer vector, String phase, Integer load, String tier, List<PatronView> patrons,
            Integer backing, Integer net, Integer rank, Integer percentile, boolean quota, LocalDate onset, Long age, Long decay, boolean active, Integer effective,
            Integer clout, Integer influence, Integer brace, Integer tide, Integer strain, String pulse, List<String> regions, Integer reach, Integer gust, Integer coil,
            List<PatronView> allies, int allyCount, Integer synergy, Integer combined, Integer score, int noteSum, Integer quality, Integer adjusted, String band, Integer scaled, Integer thrum, Integer spool, String drag, Integer lift, Integer yield, Integer grip, Integer charge, Integer churn, Integer flow, Integer norm, boolean outlier, String stratum, Integer portion, Integer standing, State state, Integer ebb, boolean stray,
            Long groupId, String groupName, List<GroupController.GroupView> groups) {
        static PersonView of(Person p, Map<Long, Integer> ranks, Map<Long, Integer> standings, Integer flooredMeanScaled, Double meanScaled, Long grandScaled, Integer meanBlend, LocalDate anchor) {
            Integer rank = ranks.get(p.getLocalId());
            return new PersonView(p.getLocalId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.getDelta(), p.getEpsilon(), p.getZeta(),
                    p.getGrade(), p.getWeight(), p.getKind(), p.getVerge(), p.getThrust(), p.getDrift(), p.getMomentum(), p.getForce(), p.getSway(), p.getTorque(), p.getPivot(), p.getBlend(), p.getFlux(), p.getCrest(), p.getSwing(), p.getHaul(), p.getVector(), p.getPhase(), p.getLoad(), p.getTier(),
                    p.getPatrons().stream().map(o -> new PatronView(o.getLocalId(), o.getName())).toList(),
                    p.getBacking(), p.getNet(), rank, PersonController.percentile(rank, ranks.size()), PersonController.quota(rank, ranks.size()), p.getOnset(),
                    p.getAge(anchor), p.getDecay(anchor), p.isActive(), p.getEffective(), p.getClout(),
                    p.getInfluence(), p.getBrace(), p.getTide(), p.getStrain(), p.getPulse(), p.getRegions(), p.getReach(), p.getGust(), p.getCoil(),
                    p.getAllies().stream().map(o -> new PatronView(o.getLocalId(), o.getName())).toList(),
                    p.getAllies().size(), p.getSynergy(), p.getCombined(), p.getScore(), p.getNoteSum(), p.getQuality(), p.getAdjusted(), p.getBand(), p.getScaled(), p.getThrum(), p.getSpool(), p.getDrag(), p.getLift(), p.getYield(), p.getGrip(), p.getCharge(), p.getChurn(), p.getFlow(),
                    PersonController.norm(p.getScaled(), flooredMeanScaled),
                    PersonController.outlier(PersonController.norm(p.getScaled(), flooredMeanScaled)),
                    PersonController.stratum(p.getScaled(), meanScaled),
                    PersonController.portion(p.getScaled(), grandScaled), standings.get(p.getLocalId()), p.getState(),
                    PersonController.ebb(p.getBlend(), meanBlend),
                    PersonController.stray(PersonController.ebb(p.getBlend(), meanBlend)),
                    p.getGroup() == null ? null : p.getGroup().getLocalId(),
                    p.getGroup() == null ? null : p.getGroup().getName(),
                    p.getGroups().stream().map(GroupController.GroupView::of).toList());
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

    /**
     * Whether a rank is in the top third of the ranked people, the third rounded down so that only a
     * whole third is taken and any remainder is dropped. Unranked is not.
     */
    static boolean quota(Integer rank, int ranked) {
        return rank != null && rank <= ranked / 3;
    }

    /** Norm is scaled minus the sheet's mean scaled (rounded down); absent while either is absent. */
    static Integer norm(Integer scaled, Integer flooredMeanScaled) {
        return scaled == null || flooredMeanScaled == null ? null : scaled - flooredMeanScaled;
    }

    /** Stratum is TOP when scaled is at or above the sheet's mean scaled, otherwise BOT; absent while either is absent. */
    static String stratum(Integer scaled, Double meanScaled) {
        return scaled == null || meanScaled == null ? null : scaled >= meanScaled ? "TOP" : "BOT";
    }

    /**
     * Portion is scaled as a whole-number percentage of the sheet's grand scaled, rounded down; absent
     * while scaled is absent or the grand scaled is absent or zero.
     */
    static Integer portion(Integer scaled, Long grandScaled) {
        return scaled == null || grandScaled == null || grandScaled == 0 ? null : (int) Math.floorDiv(scaled * 100L, grandScaled);
    }

    /** Ebb is blend minus the sheet's mean blend (rounded down); absent while either is absent. */
    static Integer ebb(Integer blend, Integer meanBlend) {
        return blend == null || meanBlend == null ? null : blend - meanBlend;
    }

    /** A stray has an ebb more than 100 away from zero in either direction; without an ebb, not a stray. */
    static boolean stray(Integer ebb) {
        return ebb != null && Math.abs(ebb) > 100;
    }

    /** An outlier has a norm more than 100 away from zero in either direction; without a norm, not an outlier. */
    static boolean outlier(Integer norm) {
        return norm != null && Math.abs(norm) > 100;
    }

    /**
     * The mean scaled of the people whose scaled can be worked out, rounded down. Absent when there are
     * none.
     */
    private static Integer flooredMeanScaled(List<Person> people) {
        List<Integer> values = people.stream().map(Person::getScaled).filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        long sum = values.stream().mapToLong(Integer::longValue).sum();
        return (int) Math.floorDiv(sum, (long) values.size());
    }

    /** The mean scaled of the people whose scaled can be worked out, unrounded. Absent when there are none. */
    private static Double meanScaled(List<Person> people) {
        List<Integer> values = people.stream().map(Person::getScaled).filter(v -> v != null).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Integer::longValue).average().getAsDouble();
    }

    /** The grand scaled: the sum of scaled over the people whose scaled can be worked out. Absent when there are none. */
    private static Long grandScaled(List<Person> people) {
        List<Integer> values = people.stream().map(Person::getScaled).filter(v -> v != null).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Integer::longValue).sum();
    }

    /**
     * The mean blend of the people whose blend can be worked out, rounded down. Absent when there are
     * none.
     */
    private static Integer meanBlend(List<Person> people) {
        List<Integer> values = people.stream().map(Person::getBlend).filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        long sum = values.stream().mapToLong(Integer::longValue).sum();
        return (int) Math.floorDiv(sum, (long) values.size());
    }

    /** Highest momentum first; equal momentum by higher alpha, then by name, earlier in the alphabet first. */
    private static final Comparator<Person> RANK_ORDER = Comparator
            .comparing(Person::getMomentum, Comparator.reverseOrder())
            .thenComparing(Person::getAlpha, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Person::getName)
            .thenComparing(Person::getLocalId);

    public record PatronView(Long id, String name) {
    }

    public record NewPerson(@NotBlank String name, Integer alpha, Integer beta, Integer gamma, Integer delta, Integer epsilon, Integer zeta, Grade grade, Kind kind, List<String> regions, Long groupId, List<Long> groupIds) {
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
        Map<Long, Integer> standings = standings(people);
        Integer flooredMeanScaled = flooredMeanScaled(people);
        Double meanScaled = meanScaled(people);
        Long grandScaled = grandScaled(people);
        Integer meanBlend = meanBlend(people);
        LocalDate anchor = service.anchor(sheetId);
        return people.stream().map(p -> PersonView.of(p, ranks, standings, flooredMeanScaled, meanScaled, grandScaled, meanBlend, anchor)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView add(@RequestParam(required = false) String sheet, @Valid @RequestBody NewPerson person) {
        try {
            return view(sheetId(sheet), service.add(sheetId(sheet), person.name(), person.alpha(), person.beta(), person.gamma(),
                    person.delta(), person.epsilon(), person.zeta(), person.grade(), person.kind(), regions(person.regions()),
                    groupIds(person.groupId(), person.groupIds())));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Moves the person with this id on the request's sheet forward to their next state (REWORK back to FINAL); refused once FINAL. */
    @PostMapping("/{id}/advance")
    public PersonView advance(@RequestParam(required = false) String sheet, @PathVariable long id) {
        String sheetId = sheetId(sheet);
        try {
            return service.advance(sheetId, id).map(p -> view(sheetId, p))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Reopens the FINAL person with this id on the request's sheet to REWORK; refused unless FINAL. */
    @PostMapping("/{id}/rework")
    public PersonView rework(@RequestParam(required = false) String sheet, @PathVariable long id) {
        String sheetId = sheetId(sheet);
        try {
            return service.rework(sheetId, id).map(p -> view(sheetId, p))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Moves the person with this id on the request's sheet back to their previous state; refused once FINAL, at REWORK or at DRAFT. */
    @PostMapping("/{id}/demote")
    public PersonView demote(@RequestParam(required = false) String sheet, @PathVariable long id) {
        String sheetId = sheetId(sheet);
        try {
            return service.demote(sheetId, id).map(p -> view(sheetId, p))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Sets, on the person with this id on the request's sheet, whichever of alpha, beta, gamma, delta, epsilon, zeta, score, grade, kind, regions (a list of codes), active
     * (true or false), patronIds and allyIds (lists of ids on that sheet) and groupId (a group's id on that sheet, putting the person in just that group) or groupIds (a list of group ids on that sheet, the groups the person is in) the body names; a null value clears it.
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
        if (update.containsKey("delta")) {
            person = service.setDelta(sheetId, id, integer(update.get("delta")));
        }
        if (update.containsKey("epsilon")) {
            person = service.setEpsilon(sheetId, id, integer(update.get("epsilon")));
        }
        if (update.containsKey("zeta")) {
            person = service.setZeta(sheetId, id, integer(update.get("zeta")));
        }
        if (update.containsKey("score")) {
            person = service.setScore(sheetId, id, integer(update.get("score")));
        }
        if (update.containsKey("grade")) {
            person = service.setGrade(sheetId, id, grade(update.get("grade")));
        }
        if (update.containsKey("kind")) {
            person = service.setKind(sheetId, id, kind(update.get("kind")));
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
        if (update.containsKey("groupId") || update.containsKey("groupIds")) {
            List<Long> groupIds = new ArrayList<>();
            if (update.get("groupIds") instanceof List<?> ids) {
                for (Object groupId : ids) {
                    groupIds.add(integer(groupId).longValue());
                }
            } else if (update.get("groupIds") != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupIds must be a list");
            } else if (update.get("groupId") != null) {
                // A single groupId puts the person in just that group.
                groupIds.add(integer(update.get("groupId")).longValue());
            }
            try {
                person = service.setGroups(sheetId, id, groupIds);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        return person.map(p -> view(sheetId, p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The groups a new person is put in: the listed ones, or the single one given, or none. */
    private static List<Long> groupIds(Long groupId, List<Long> groupIds) {
        if (groupIds != null) {
            return groupIds;
        }
        return groupId == null ? List.of() : List.of(groupId);
    }

    /** A person's view, ranked among the people on their sheet. */
    private PersonView view(String sheetId, Person person) {
        List<Person> people = service.list(sheetId);
        return PersonView.of(person, ranks(people), standings(people), flooredMeanScaled(people), meanScaled(people), grandScaled(people), meanBlend(people), service.anchor(sheetId));
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

    /**
     * Each person's sheet standing by local id: 1 for the highest scaled, then 2, 3, ... with equal
     * scaled ordered by name. People without a scaled have no standing.
     */
    private static Map<Long, Integer> standings(List<Person> people) {
        List<Person> ordered = people.stream().filter(p -> p.getScaled() != null)
                .sorted(Comparator.comparing(Person::getScaled, Comparator.reverseOrder()).thenComparing(Person::getName)).toList();
        Map<Long, Integer> standings = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            standings.put(ordered.get(i).getLocalId(), i + 1);
        }
        return standings;
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

    private static Kind kind(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Kind.parse(String.valueOf(value));
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
