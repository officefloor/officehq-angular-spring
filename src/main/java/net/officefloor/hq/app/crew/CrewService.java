package net.officefloor.hq.app.crew;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the crews of a sheet, each name at most once, each with a lead from the sheet's people, and their members: a
 * person is in a crew at most once, and never as both its lead and a member. A person may be in several crews. A crew
 * is also a pool under its name, so no crew takes a pool's name.
 */
@Service
public class CrewService {

    private final CrewRepository crews;
    private final PoolRepository pools;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public CrewService(CrewRepository crews, PoolRepository pools, PersonRepository people, SheetRepository sheets,
            Audit audit, RollupService rollup) {
        this.rollup = rollup;
        this.crews = crews;
        this.pools = pools;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Crew> list(String sheetId) {
        return crews.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * The people a restricted user sees: the person with the user's name, and the lead and members of every crew that
     * person leads or belongs to; none when the sheet has no person by that name.
     */
    @Transactional(readOnly = true)
    public Set<Long> peopleOf(String sheetId, String user) {
        Set<Long> ids = new HashSet<>();
        people.findBySheetIdAndNameKey(sheetId, Person.key(user)).ifPresent(self -> {
            ids.add(self.getId());
            for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
                if (crew.includes(self.getId())) {
                    if (crew.getLeadId() != null) {
                        ids.add(crew.getLeadId());
                    }
                    crew.getMembers().forEach(m -> ids.add(m.getPersonId()));
                }
            }
        });
        return ids;
    }

    /** Add a crew by its name (already trimmed, not blank) with its lead; refused when the sheet has a crew or a pool by that name. */
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
        if (pools.existsBySheetIdAndName(sheetId, name)) {
            throw new CrewRefusedException(name + " is already a pool on sheet " + sheetId);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Crew saved;
        try {
            saved = crews.saveAndFlush(new Crew(sheetId, name, leadId));
        } catch (DataIntegrityViolationException e) {
            throw new CrewRefusedException(name + " is already a crew on sheet " + sheetId);
        }
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " lead=" + lead.getName());
        // A crew is a pool, and a cut counts against the cap.
        rollup.recordChanges(sheetId, user, before);
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
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        crew.getMembers().add(new CrewMember(personId));
        try {
            crews.saveAndFlush(crew);
        } catch (DataIntegrityViolationException e) {
            throw new CrewRefusedException(person.getName() + " is already in crew " + crew.getName());
        }
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        rollup.recordChanges(sheetId, user, before);
        return crew;
    }
}
