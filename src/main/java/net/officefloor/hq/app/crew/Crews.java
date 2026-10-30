package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crews on a sheet, sorted by name: each with a unique, non-blank name and a lead, a person of the sheet, and
 * its members, people of the sheet each in the crew at most once; the lead is never a member of their own crew.
 */
@Service
public class Crews {

    /** The longest a crew's name may be. */
    static final int NAME_MAX = 100;

    /** A person as shown in a crew. */
    public record Who(Long personId, String name) {
    }

    /** A crew as shown: its lead and its members in the order they were added. */
    public record Shown(String name, Who lead, List<Who> members) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public Crews(CrewRepository crews, CrewMemberRepository members, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, ApplicationEventPublisher events) {
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The crews on the sheet, sorted by name, each with its lead and members. */
    @Transactional(readOnly = true)
    public List<Shown> list(String sheetId) {
        List<Crew> all = crews.findBySheetIdOrderByNameAsc(sheetId);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = members.findByCrewIdInOrderByIdAsc(all.stream().map(Crew::getId).toList())
                .stream().collect(Collectors.groupingBy(CrewMember::getCrewId));
        return all.stream().map(c -> new Shown(c.getName(), who(byId.get(c.getLeadId())),
                byCrew.getOrDefault(c.getId(), List.of()).stream().map(m -> byId.get(m.getPersonId()))
                        .filter(p -> p != null).map(Crews::who).toList()))
                .toList();
    }

    private static Who who(Person p) {
        return p == null ? null : new Who(p.getId(), p.getName());
    }

    /** Add a crew under its name, trimmed, led by a person of the sheet; refused when the name is blank, too long or already a crew's. */
    @Transactional
    public void add(String sheetId, String user, String text, Long leadId) {
        String name = text == null ? "" : text.strip();
        if (name.isEmpty()) {
            throw new CrewRefused("Enter a name for the crew.");
        }
        if (name.length() > NAME_MAX) {
            throw new CrewRefused("The crew's name is too long.");
        }
        if (leadId == null) {
            throw new CrewRefused("Choose a lead for the crew.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new CrewRefused("Choose a lead on this sheet."));
        if (crews.findBySheetIdAndName(sheetId, name).isPresent()) {
            throw new CrewRefused("There is already a crew named " + name + ".");
        }
        crews.saveAndFlush(new Crew(sheetId, name, lead.getId()));
        events.publishEvent(new CrewAdded(sheetId, user, name, lead.getName()));
    }

    /** Add a person of the sheet to the named crew; refused when they lead it or are in it already. */
    @Transactional
    public void addMember(String sheetId, String user, String crewName, Long personId) {
        if (personId == null) {
            throw new CrewRefused("Choose a person.");
        }
        sheetRows.lockById(sheetId);
        Crew crew = crews.findBySheetIdAndName(sheetId, crewName == null ? "" : crewName.strip())
                .orElseThrow(() -> new CrewRefused("No such crew on this sheet."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new CrewRefused("Choose a person on this sheet."));
        if (crew.getLeadId().equals(person.getId())) {
            throw new CrewRefused(person.getName() + " leads the crew.");
        }
        if (members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new CrewRefused(person.getName() + " is in the crew already.");
        }
        members.saveAndFlush(new CrewMember(crew.getId(), person.getId()));
        events.publishEvent(new CrewMemberAdded(sheetId, user, crew.getName(), person.getName()));
    }

    /**
     * The merged person's crews move to the other: crews they led are led by the other, their memberships become the
     * other's, a membership dropped where the other leads that crew or is in it already.
     */
    @Transactional
    public void merge(long personId, long intoId) {
        for (Crew crew : crews.findByLeadId(personId)) {
            crew.setLeadId(intoId);
            members.deleteAll(members.findByCrewId(crew.getId()).stream()
                    .filter(m -> m.getPersonId().equals(intoId)).toList());
        }
        crews.flush();
        members.flush();
        for (CrewMember m : members.findByPersonId(personId)) {
            Crew crew = crews.findById(m.getCrewId()).orElseThrow();
            if (crew.getLeadId().equals(intoId) || members.existsByCrewIdAndPersonId(crew.getId(), intoId)) {
                members.delete(m);
            } else {
                m.setPersonId(intoId);
            }
            members.flush();
        }
    }

    /** The person leaves the sheet: out of every crew, and the crews they led are gone with their members. */
    @Transactional
    public void remove(long personId) {
        members.deleteAll(members.findByPersonId(personId));
        members.flush();
        for (Crew crew : crews.findByLeadId(personId)) {
            members.deleteAll(members.findByCrewId(crew.getId()));
            members.flush();
            crews.delete(crew);
        }
        crews.flush();
    }

    /** Copy the sheet's crews to the clone, its people mapped to theirs by id. */
    @Transactional
    public void cloneTo(String sheetId, String toSheet, Map<Long, Long> personIds) {
        for (Crew crew : crews.findBySheetIdOrderByNameAsc(sheetId)) {
            Crew copy = crews.save(new Crew(toSheet, crew.getName(), personIds.get(crew.getLeadId())));
            for (CrewMember m : members.findByCrewId(crew.getId())) {
                members.save(new CrewMember(copy.getId(), personIds.get(m.getPersonId())));
            }
        }
    }
}
