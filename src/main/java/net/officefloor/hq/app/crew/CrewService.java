package net.officefloor.hq.app.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The crews on a sheet, each with its lead and members. */
@Service
public class CrewService {

    /** A crew as it stands: its lead (null once the lead has left the sheet) and its members, in the order added. */
    public record CrewWithMembers(Crew crew, Person lead, List<Person> members) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public CrewService(CrewRepository crews, CrewMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's crews, in the order they were added, each with its lead and members. */
    @Transactional(readOnly = true)
    public List<CrewWithMembers> list(String sheetId) {
        List<Crew> list = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = members.findByCrewIdInOrderByIdAsc(list.stream().map(Crew::getId).toList())
                .stream().collect(Collectors.groupingBy(CrewMember::getCrewId));
        return list.stream().map(c -> new CrewWithMembers(c, c.getLeadId() == null ? null : byId.get(c.getLeadId()),
                byCrew.getOrDefault(c.getId(), List.of()).stream().map(m -> byId.get(m.getPersonId()))
                        .filter(p -> p != null).toList())).toList();
    }

    /** Add a crew by its name, trimmed, led by a person of the sheet; refused when the name is blank or already a
     *  crew on the sheet. */
    @Transactional
    public CrewWithMembers add(String sheetId, String user, String name, Long leadId) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A crew has a name");
        }
        if (leadId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A crew has a lead");
        }
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (crews.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That crew is already on the sheet");
        }
        Crew saved;
        try {
            saved = crews.saveAndFlush(new Crew(sheetId, key, lead.getId()));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That crew is already on the sheet");
        }
        audit.recordChangeOnCommit(sheetId, user, "CREW_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " lead=" + lead.getName());
        return new CrewWithMembers(saved, lead, List.of());
    }

    /** Add a person of the sheet to the crew; refused when they are its lead or already in it. */
    @Transactional
    public CrewWithMembers addMember(String sheetId, String user, long crewId, Long personId) {
        if (personId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A member is a person");
        }
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Crew crew = crews.findById(crewId).filter(c -> c.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew on this sheet"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.getId().equals(crew.getLeadId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person leads the crew");
        }
        if (members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew");
        }
        try {
            members.saveAndFlush(new CrewMember(crew.getId(), person.getId()));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew");
        }
        audit.recordChangeOnCommit(sheetId, user, "CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user
                + " crew=" + crew.getName() + " name=" + person.getName());
        return list(sheetId).stream().filter(c -> c.crew().getId().equals(crew.getId())).findFirst().orElseThrow();
    }

    /** A person leaves the sheet: the crews they lead have no lead, and their memberships go. */
    @Transactional
    public void removePerson(Long personId) {
        for (Crew crew : crews.findByLeadId(personId)) {
            crew.setLeadId(null);
        }
        crews.flush();
        members.deleteAll(members.findByPersonIdOrderByIdAsc(personId));
        members.flush();
    }

    /** A person is merged into another: the crews they lead are led by the other, and their memberships move to the
     *  other, dropped where the other already leads or is in the crew. */
    @Transactional
    public void reassign(Long fromId, Long intoId) {
        for (Crew crew : crews.findByLeadId(fromId)) {
            crew.setLeadId(intoId);
            members.deleteAll(members.findByPersonIdOrderByIdAsc(intoId).stream()
                    .filter(m -> m.getCrewId().equals(crew.getId())).toList());
        }
        crews.flush();
        List<CrewMember> gone = new ArrayList<>();
        for (CrewMember member : members.findByPersonIdOrderByIdAsc(fromId)) {
            Crew crew = crews.findById(member.getCrewId()).orElseThrow();
            if (intoId.equals(crew.getLeadId()) || members.existsByCrewIdAndPersonId(crew.getId(), intoId)) {
                gone.add(member);
            } else {
                member.moveTo(intoId);
            }
        }
        members.deleteAll(gone);
        members.flush();
    }
}
