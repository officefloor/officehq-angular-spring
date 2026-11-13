package net.officefloor.hq.app.crew;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's crews, their leads and their members. */
@Service
public class CrewService {

    private final CrewRepository crews;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public CrewService(CrewRepository crews, PersonRepository people, SheetService sheets, Audit audit) {
        this.crews = crews;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's crews, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Crew> list(String sheetId) {
        return crews.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a crew by a name (surrounding spaces removed) with a lead from the sheet's people; a blank name, one
     *  already taken, or no lead, is refused. */
    @Transactional
    public Crew add(String sheetId, String user, String name, Long leadId) {
        sheets.today(sheetId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new CrewRefusedException("Enter a crew name.");
        }
        if (crews.existsBySheetIdAndName(sheetId, n)) {
            throw new CrewRefusedException("\"" + n + "\" is already a crew.");
        }
        Person lead = leadId == null ? null : people.findByIdAndSheetId(leadId, sheetId).orElse(null);
        if (lead == null) {
            throw new CrewRefusedException("Choose a lead.");
        }
        Crew saved = crews.saveAndFlush(new Crew(sheetId, n, lead.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + n + " lead=" + lead.getName());
        return saved;
    }

    /** Add a person of the sheet to a crew; its lead, or a person already in it, is refused. */
    @Transactional
    public Crew addMember(String sheetId, String user, long crewId, Long personId) {
        Crew crew = crews.findByIdAndSheetId(crewId, sheetId)
                .orElseThrow(() -> new CrewRefusedException("No such crew."));
        Person person = personId == null ? null : people.findByIdAndSheetId(personId, sheetId).orElse(null);
        if (person == null) {
            throw new CrewRefusedException("Choose a person.");
        }
        if (person.getId().equals(crew.getLeadId())) {
            throw new CrewRefusedException(person.getName() + " leads " + crew.getName() + ".");
        }
        if (crew.includes(person.getId())) {
            throw new CrewRefusedException(person.getName() + " is already in " + crew.getName() + ".");
        }
        crew.addMember(person.getId());
        Crew saved = crews.saveAndFlush(crew);
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return saved;
    }
}
