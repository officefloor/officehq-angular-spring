package net.officefloor.hq.app.crew;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the crews of a sheet, each name at most once, each with a lead from the sheet's people, and their members: a
 * person is in a crew at most once, and never as both its lead and a member. A person may be in several crews.
 */
@Service
public class CrewService {

    private final CrewRepository crews;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;

    public CrewService(CrewRepository crews, PersonRepository people, SheetRepository sheets, Audit audit) {
        this.crews = crews;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Crew> list(String sheetId) {
        return crews.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a crew by its name (already trimmed, not blank) with its lead; refused when the sheet has one by that name. */
    @Transactional
    public Crew add(String sheetId, String user, String name, Long leadId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, leadId));
        if (crews.existsBySheetIdAndName(sheetId, name)) {
            throw new CrewRefusedException(name + " is already a crew on sheet " + sheetId);
        }
        Crew saved;
        try {
            saved = crews.saveAndFlush(new Crew(sheetId, name, leadId));
        } catch (DataIntegrityViolationException e) {
            throw new CrewRefusedException(name + " is already a crew on sheet " + sheetId);
        }
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " lead=" + lead.getName());
        return saved;
    }

    /** Add the person to the crew; refused when they are its lead or already a member. */
    @Transactional
    public Crew addMember(String sheetId, String user, Long crewId, Long personId) {
        Crew crew = crews.findByIdAndSheetId(crewId, sheetId)
                .orElseThrow(() -> new CrewRefusedException("No crew " + crewId + " on sheet " + sheetId));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        if (personId.equals(crew.getLeadId())) {
            throw new CrewRefusedException(person.getName() + " leads crew " + crew.getName());
        }
        if (crew.includes(personId)) {
            throw new CrewRefusedException(person.getName() + " is already in crew " + crew.getName());
        }
        crew.getMembers().add(new CrewMember(personId));
        try {
            crews.saveAndFlush(crew);
        } catch (DataIntegrityViolationException e) {
            throw new CrewRefusedException(person.getName() + " is already in crew " + crew.getName());
        }
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return crew;
    }
}
