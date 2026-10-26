package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CrewService {

    private final CrewRepository crews;
    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public CrewService(CrewRepository crews, PoolRepository pools, PersonRepository people, SheetService sheets, SheetRepository sheetRows,
            Audit audit) {
        this.crews = crews;
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's crews in the order they were added, each with its members. */
    @Transactional(readOnly = true)
    public List<Crew> list(String sheetId) {
        return crews.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a crew under its name (trimmed) with a person of the sheet as its lead, refused when the name is
     *  blank or the sheet has a crew or a pool of that name (a crew is also a pool under its name). */
    @Transactional
    public Crew add(String sheetId, String user, String name, Long leadId) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Type a crew name.");
        }
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Person lead = personOf(sheetId, leadId);
        if (crews.existsBySheetIdAndName(sheetId, key)) throw alreadyCrew(key);
        if (pools.existsBySheetIdAndName(sheetId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet already has a pool \"" + key + "\".");
        }
        Crew saved;
        try {
            saved = crews.saveAndFlush(new Crew(sheetId, key, lead.getId()));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyCrew(key);
        }
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " lead=" + lead.getName());
        return saved;
    }

    /** Add a person of the sheet to a crew's members; refused when they are its lead or already a member. */
    @Transactional
    public Crew addMember(String sheetId, String user, long crewId, Long personId) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Crew crew = crews.findById(crewId).filter((c) -> c.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew."));
        Person person = personOf(sheetId, personId);
        if (crew.isLead(person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " leads the crew " + crew.getName() + ".");
        }
        if (crew.hasMember(person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in the crew " + crew.getName() + ".");
        }
        crew.addMember(person.getId());
        try {
            crew = crews.saveAndFlush(crew);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is already in the crew " + crew.getName() + ".");
        }
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return crew;
    }

    private Person personOf(String sheetId, Long personId) {
        return (personId == null ? Optional.<Person>empty() : people.findByIdAndSheetId(personId, sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Choose a person of the sheet."));
    }

    private static ResponseStatusException alreadyCrew(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The sheet already has a crew \"" + name + "\".");
    }
}
