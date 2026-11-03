package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The crews of a sheet: each with a lead and its members. Each crew is also a pool of the sheet under
 *  its name, its lead and members the pool's members. */
@Service
public class CrewService {

    /** A crew with its lead (null when they have left the sheet) and its members in the order they
     *  were added (people who have left the sheet left out). */
    public record Listed(Crew crew, Person lead, List<Person> members) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final PoolRepository pools;
    private final SheetService sheets;
    private final Audit audit;
    private final Rollup rollup;

    public CrewService(CrewRepository crews, CrewMemberRepository members, PersonRepository people,
            PoolRepository pools, SheetService sheets, Audit audit,
            @Lazy Rollup rollup) {
        this.rollup = rollup;
        this.pools = pools;
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The crews of the sheet in the order they were added, each with its lead and members. */
    @Transactional(readOnly = true)
    public List<Listed> list(String sheetId) {
        List<Crew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = members
                .findByCrewIdInOrderByIdAsc(all.stream().map(Crew::getId).toList()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        return all.stream().map(c -> new Listed(c, byId.get(c.getLeadPersonId()),
                byCrew.getOrDefault(c.getId(), List.of()).stream()
                        .map(m -> byId.get(m.getPersonId()))
                        .filter(p -> p != null)
                        .toList()))
                .toList();
    }

    /** Add a crew with a (trimmed) name and a lead; refused when the name is blank or already a crew
     *  or a pool of the sheet, or the lead is not a person of it. Its pool is added with it. */
    @Transactional
    public synchronized Listed add(String sheetId, String user, String name, long leadId) {
        return rollup.tracking(sheetId, user, () -> {
            String text = name == null ? "" : name.trim();
            if (text.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a crew name");
            }
            sheets.load(sheetId, user);
            Person lead = people.findByIdAndSheetId(leadId, sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
            if (crews.existsBySheetIdAndName(sheetId, text) || pools.existsBySheetIdAndName(sheetId, text)) {
                throw alreadyACrew();
            }
            Crew saved;
            try {
                saved = crews.saveAndFlush(new Crew(sheetId, text, lead.getId()));
                Pool pool = Pool.ofCrew(sheetId, text, saved.getId());
                pool.setAddedOn(sheets.today(sheetId));
                pools.saveAndFlush(pool);
            } catch (DataIntegrityViolationException e) {
                throw alreadyACrew();
            }
            audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + text
                    + " lead=" + lead.getName());
            return new Listed(saved, lead, List.of());
        });
    }

    /** Add a person of the sheet to a crew; refused when they are its lead or already in it. */
    @Transactional
    public synchronized Listed addMember(String sheetId, String user, long crewId, long personId) {
        return rollup.tracking(sheetId, user, () -> {
            Crew crew = crews.findByIdAndSheetId(crewId, sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew"));
            Person person = people.findByIdAndSheetId(personId, sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
            if (crew.getLeadPersonId().equals(personId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That person leads the crew");
            }
            if (members.existsByCrewIdAndPersonId(crewId, personId)) {
                throw alreadyAMember();
            }
            try {
                CrewMember m = new CrewMember(crewId, personId);
                m.setAddedOn(sheets.today(sheetId));
                members.saveAndFlush(m);
            } catch (DataIntegrityViolationException e) {
                throw alreadyAMember();
            }
            audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                    + " name=" + person.getName());
            return list(sheetId).stream().filter(l -> l.crew().getId().equals(crewId)).findFirst().orElseThrow();
        });
    }

    private static ResponseStatusException alreadyACrew() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already a crew or a pool");
    }

    private static ResponseStatusException alreadyAMember() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew");
    }
}
