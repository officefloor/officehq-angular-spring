package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CrewService {

    /** A crew with its members, in the order they were added. */
    public record CrewWithMembers(Crew crew, List<CrewMember> members) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public CrewService(CrewRepository crews, CrewMemberRepository members, PersonRepository people,
            SheetService sheets, Audit audit) {
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's crews in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<CrewWithMembers> list(String sheetId) {
        List<Crew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<CrewMember>> byCrew = members.findByCrewIdInOrderByIdAsc(all.stream().map(Crew::getId).toList())
                .stream().collect(Collectors.groupingBy(CrewMember::getCrewId));
        return all.stream().map(c -> new CrewWithMembers(c, byCrew.getOrDefault(c.getId(), List.of()))).toList();
    }

    /** Add a crew led by a person of the sheet; refused (404) for a person not on the sheet, (409)
     *  when the sheet has a crew of the name already. */
    @Transactional
    public Crew add(String sheetId, String user, String name, long leadId) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (crews.findBySheetIdAndNameIgnoreCase(sheetId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet has a crew of that name");
        }
        Crew crew = crews.saveAndFlush(new Crew(sheetId, name, lead.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + name
                + " lead=" + lead.getName());
        return crew;
    }

    /** Add a person of the sheet to a crew; refused (404) for a crew or person not on the sheet,
     *  (409) when the person is the crew's lead or in the crew already. */
    @Transactional
    public CrewMember addMember(String sheetId, String user, String crewName, long personId) {
        sheets.lock(sheetId);
        Crew crew = crews.findBySheetIdAndNameIgnoreCase(sheetId, crewName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (crew.getLeadId().equals(person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person leads the crew");
        }
        if (members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person is in the crew already");
        }
        CrewMember member = members.saveAndFlush(new CrewMember(crew.getId(), person.getId()));
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return member;
    }

    /** Carry a merged person's crews to the person they are merged into: the crews they lead are
     *  led by the other (who leaves the crew's members if among them), and their memberships move to
     *  the other unless the other leads the crew or is in it already. */
    @Transactional
    public void merge(Long fromId, Long intoId) {
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(fromId)) {
            crew.setLeadId(intoId);
            members.findByCrewIdInOrderByIdAsc(List.of(crew.getId())).stream()
                    .filter(m -> m.getPersonId().equals(intoId)).forEach(members::delete);
        }
        crews.flush();
        for (CrewMember member : members.findByPersonIdOrderByIdAsc(fromId)) {
            Crew crew = crews.findById(member.getCrewId()).orElseThrow();
            if (crew.getLeadId().equals(intoId) || members.existsByCrewIdAndPersonId(crew.getId(), intoId)) {
                members.delete(member);
            } else {
                member.setPersonId(intoId);
            }
        }
        members.flush();
    }

    /** Drop a person leaving the sheet from its crews: their memberships, and the crews they lead
     *  with those crews' members. */
    @Transactional
    public void drop(Long personId) {
        members.deleteAll(members.findByPersonIdOrderByIdAsc(personId));
        members.flush();
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(personId)) {
            members.deleteAll(members.findByCrewIdInOrderByIdAsc(List.of(crew.getId())));
            members.flush();
            crews.delete(crew);
        }
        crews.flush();
    }
}
