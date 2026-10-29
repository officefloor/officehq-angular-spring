package net.officefloor.hq.app.crew;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's crews and their members. A crew has a name (trimmed, not blank, at most once on the sheet) and a lead
 * chosen from the sheet's people; its members are other people of the sheet, each at most once, never its lead. A
 * person may lead or be in several crews.
 */
@Service
public class CrewService {

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

    /** The sheet's crews, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Crew> list(String sheetId) {
        return crews.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The members of every crew on the sheet, in the order they were added. */
    @Transactional(readOnly = true)
    public List<CrewMember> members(String sheetId) {
        return members.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Adds a crew under its name, trimmed, led by the person; refused when blank, already a crew's name, or no lead. */
    @Transactional
    public Crew add(String by, String sheetId, String name, Long leadId) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Enter a crew name.");
        }
        sheets.lock(sheetId);
        Person lead = person(sheetId, leadId, "Choose a lead.");
        if (crews.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, key + " is already a crew.");
        }
        Crew added = crews.saveAndFlush(new Crew(sheetId, key, lead.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + by + " name=" + key + " lead=" + lead.getName());
        return added;
    }

    /** Adds the person to the crew; refused when they lead it or are already in it. */
    @Transactional
    public CrewMember addMember(String by, String sheetId, Long crewId, Long personId) {
        sheets.lock(sheetId);
        Crew crew = crews.findById(crewId).filter(c -> c.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew."));
        Person person = person(sheetId, personId, "Choose a person.");
        if (person.getId().equals(crew.getLeadId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " leads " + crew.getName() + ".");
        }
        if (members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in " + crew.getName() + ".");
        }
        CrewMember added = members.saveAndFlush(new CrewMember(sheetId, crew.getId(), person.getId()));
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + by + " crew=" + crew.getName()
                + " name=" + person.getName());
        return added;
    }

    /**
     * A person merged into another: the crews they lead pass to the other, and their memberships too; a membership the
     * other already has, or of a crew the other now leads, is dropped.
     */
    @Transactional
    public void merged(String sheetId, Long from, Long into) {
        List<Crew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        for (Crew c : all) {
            if (from.equals(c.getLeadId())) {
                c.setLeadId(into);
                crews.save(c);
            }
        }
        crews.flush();
        List<CrewMember> memberships = members.findBySheetIdOrderByIdAsc(sheetId);
        for (CrewMember m : memberships) {
            boolean intoLeads = all.stream().anyMatch(c -> c.getId().equals(m.getCrewId()) && into.equals(c.getLeadId()));
            if (into.equals(m.getPersonId()) && intoLeads) {
                members.delete(m);
            } else if (from.equals(m.getPersonId())) {
                boolean intoIn = memberships.stream()
                        .anyMatch(o -> o.getCrewId().equals(m.getCrewId()) && into.equals(o.getPersonId()));
                if (intoIn || intoLeads) {
                    members.delete(m);
                } else {
                    m.setPersonId(into);
                    members.save(m);
                }
            }
        }
        members.flush();
    }

    /** A person leaving the sheet: their memberships are dropped, and so are the crews they lead, with their members. */
    @Transactional
    public void left(String sheetId, Long personId) {
        List<Crew> led = crews.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(c -> personId.equals(c.getLeadId())).toList();
        members.deleteAll(members.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(m -> personId.equals(m.getPersonId())
                        || led.stream().anyMatch(c -> c.getId().equals(m.getCrewId())))
                .toList());
        members.flush();
        crews.deleteAll(led);
        crews.flush();
    }

    private Person person(String sheetId, Long personId, String refusal) {
        if (personId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refusal);
        }
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refusal));
    }
}
