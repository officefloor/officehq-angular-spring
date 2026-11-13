package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's crews, their leads and their members. The signed-in user's name comes in the X-User header. The
 *  screen adds up a crew's due-total from its people's, as it shows them. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A person of a crew, by id, with their name. */
    public record CrewPersonView(long personId, String name) {
    }

    /** A crew: its lead and its members in the order added. */
    public record CrewView(long id, String name, CrewPersonView lead, List<CrewPersonView> members) {

        static CrewView of(Crew crew, Map<Long, Person> people) {
            return new CrewView(crew.getId(), crew.getName(), person(crew.getLeadId(), people),
                    crew.getMembers().stream().map(m -> person(m.getPersonId(), people)).toList());
        }

        private static CrewPersonView person(long id, Map<Long, Person> people) {
            Person p = people.get(id);
            return new CrewPersonView(id, p == null ? "" : p.getName());
        }
    }

    public record CrewRequest(@Size(max = 200) String name, Long leadId) {
    }

    public record CrewMemberRequest(Long personId) {
    }

    private final CrewService service;
    private final PersonRepository people;

    public CrewController(CrewService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** The sheet's crews, in the order they were added. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        String sid = sheetId.trim();
        Map<Long, Person> byId = people(sid);
        return service.list(sid).stream().map(c -> CrewView.of(c, byId)).toList();
    }

    /** Add a crew to the sheet with a lead. */
    @PostMapping
    public CrewView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CrewRequest request) {
        String sid = sheetId.trim();
        return CrewView.of(service.add(sid, user.trim(), request.name(), request.leadId()), people(sid));
    }

    /** Add a member to a crew. */
    @PostMapping("/{crewId}/members")
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CrewMemberRequest request) {
        String sid = sheetId.trim();
        return CrewView.of(service.addMember(sid, user.trim(), crewId, request.personId()), people(sid));
    }

    private Map<Long, Person> people(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
    }

    /** A crew or member that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(CrewRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(CrewRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
