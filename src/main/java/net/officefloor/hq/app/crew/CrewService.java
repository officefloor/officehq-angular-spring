package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The crews on a sheet: each a name, a lead from the sheet's people and its members. */
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

    /** The sheet's crews, in the order they were added, each with its members. */
    public List<CrewWithMembers> list(String sheetId) {
        List<Crew> list = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, List<CrewMember>> byCrew = members
                .findByCrewIdInOrderByIdAsc(list.stream().map(Crew::getId).toList()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        return list.stream().map(c -> new CrewWithMembers(c, byCrew.getOrDefault(c.getId(), List.of()))).toList();
    }

    /** Add a crew, its name trimmed, led by a person of the sheet. Refused when the name is blank or
     *  already a crew's on the sheet, or the lead is not a person of the sheet. The sheet is held
     *  locked so the same name added at the same moment is checked against itself. */
    @Transactional
    public CrewWithMembers add(String sheetId, String user, String name, Long leadId) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new CrewRefusedException("A crew needs a name");
        }
        if (leadId == null) {
            throw new CrewRefusedException("A crew needs a lead");
        }
        sheets.lock(sheetId);
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new CrewRefusedException("No such person on the sheet"));
        if (crews.existsBySheetIdAndName(sheetId, key)) {
            throw new CrewRefusedException("The crew " + key + " is on the sheet already");
        }
        Crew saved = crews.saveAndFlush(new Crew(sheetId, key, lead.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + key + " lead="
                + lead.getName());
        return new CrewWithMembers(saved, List.of());
    }

    /** Add a person of the sheet to a crew. Refused for the crew's lead or a person in it already. */
    @Transactional
    public CrewWithMembers addMember(String sheetId, String user, Long crewId, Long personId) {
        if (personId == null) {
            throw new CrewRefusedException("A member is a person");
        }
        sheets.lock(sheetId);
        Crew crew = crews.findByIdAndSheetId(crewId, sheetId)
                .orElseThrow(() -> new CrewRefusedException("No such crew on the sheet"));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new CrewRefusedException("No such person on the sheet"));
        if (crew.getLeadId().equals(personId)) {
            throw new CrewRefusedException(person.getName() + " leads the crew " + crew.getName());
        }
        if (members.existsByCrewIdAndPersonId(crewId, personId)) {
            throw new CrewRefusedException(person.getName() + " is in the crew " + crew.getName() + " already");
        }
        members.saveAndFlush(new CrewMember(crewId, personId));
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return new CrewWithMembers(crew, members.findByCrewIdInOrderByIdAsc(List.of(crewId)));
    }

    /** Make the merged person's crews the other's: the crews they lead are led by the other instead
     *  (the other leaving those crews' members), and their memberships become the other's, dropped
     *  where the other leads the crew or is in it already. */
    @Transactional
    public void merge(Long fromId, Long intoId) {
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(fromId)) {
            crew.setLeadId(intoId);
            crews.saveAndFlush(crew);
            members.findByPersonIdOrderByIdAsc(intoId).stream().filter(m -> m.getCrewId().equals(crew.getId()))
                    .forEach(members::delete);
            members.flush();
        }
        for (CrewMember member : members.findByPersonIdOrderByIdAsc(fromId)) {
            Crew crew = crews.findById(member.getCrewId()).orElseThrow();
            if (crew.getLeadId().equals(intoId) || members.existsByCrewIdAndPersonId(crew.getId(), intoId)) {
                members.delete(member);
                members.flush();
            } else {
                member.setPersonId(intoId);
                members.saveAndFlush(member);
            }
        }
    }
}
