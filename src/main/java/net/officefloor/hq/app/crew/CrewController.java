package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A member of a crew: the person and their name. */
    public record MemberView(Long personId, String name) {
    }

    /** A crew: its lead (the person and their name) and its members in the order they were added. */
    public record CrewView(Long id, String name, Long leadId, String leadName, List<MemberView> members) {
    }

    public record CrewForm(@NotNull @Size(max = 140) String name, Long leadId) {
    }

    public record MemberForm(Long personId) {
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
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = service.members(sheetId).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        return service.list(sheetId).stream()
                .map(c -> view(c, byCrew.getOrDefault(c.getId(), List.of()), byId)).toList();
    }

    /** Add a crew with its lead; a blank name, one already a crew's, or no lead, is refused. */
    @PostMapping
    @Transactional
    public CrewView add(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody CrewForm form, @SessionAttribute(SessionController.USER) String user) {
        Long id = service.add(user, sheetId, form.name(), form.leadId()).getId();
        return list(sheetId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Add a person to a crew; the crew as it now stands. */
    @PostMapping("/{crewId}/members")
    @Transactional
    public CrewView addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long crewId, @Valid @RequestBody MemberForm form,
            @SessionAttribute(SessionController.USER) String user) {
        service.addMember(user, sheetId, crewId, form.personId());
        return list(sheetId).stream().filter(c -> c.id().equals(crewId)).findFirst().orElseThrow();
    }

    private static CrewView view(Crew crew, List<CrewMember> members, Map<Long, Person> byId) {
        Person lead = byId.get(crew.getLeadId());
        return new CrewView(crew.getId(), crew.getName(), crew.getLeadId(), lead == null ? "" : lead.getName(),
                members.stream().map(m -> {
                    Person p = byId.get(m.getPersonId());
                    return new MemberView(m.getPersonId(), p == null ? "" : p.getName());
                }).toList());
    }
}
