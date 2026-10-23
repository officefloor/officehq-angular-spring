package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The crews on a sheet: listed with their lead and members, added, and given members. Each crew is also a
 * pool under its name, added with it and gone with it.
 */
@Service
public class CrewService {

    /** A crew with its lead's name and its members in the order they were added. */
    public record CrewWithMembers(Crew crew, String lead, List<Member> members) {
    }

    /** A member with the person's name. */
    public record Member(CrewMember member, String name) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final PoolRepository pools;
    private final PoolMemberRepository poolMembers;
    private final SheetService sheets;
    private final Audit audit;

    public CrewService(CrewRepository crews, CrewMemberRepository members, PersonRepository people,
            PoolRepository pools, PoolMemberRepository poolMembers, SheetService sheets, Audit audit) {
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.pools = pools;
        this.poolMembers = poolMembers;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's crews in the order they were added, with their lead and members. */
    @Transactional(readOnly = true)
    public List<CrewWithMembers> list(String sheetId) {
        List<Crew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, String> names = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName));
        Map<Long, List<Member>> byCrew = members.findByCrewIdInOrderByIdAsc(all.stream().map(Crew::getId).toList())
                .stream().map(m -> new Member(m, names.get(m.getPersonId())))
                .collect(Collectors.groupingBy(m -> m.member().getCrewId()));
        return all.stream().map(c -> new CrewWithMembers(c, names.get(c.getLeadId()),
                byCrew.getOrDefault(c.getId(), List.of()))).toList();
    }

    /**
     * Add a crew led by a person on the sheet, and its pool under its name; refused when its name, trimmed, is
     * blank or already a crew's or a pool's on the sheet.
     */
    @Transactional
    public CrewWithMembers add(String sheetId, String user, String name, long leadId) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A crew needs a name.");
        }
        sheets.lock(sheetId);
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (crews.existsBySheetIdAndName(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a crew by that name.");
        }
        if (pools.existsBySheetIdAndName(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is already a pool by that name.");
        }
        Crew saved = crews.saveAndFlush(new Crew(sheetId, trimmed, lead.getId()));
        pools.saveAndFlush(Pool.ofCrew(sheetId, trimmed, saved.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + trimmed
                + " lead=" + lead.getName());
        return new CrewWithMembers(saved, lead.getName(), List.of());
    }

    /** Add a person on the sheet to a crew; refused when they lead it or are already in it. */
    @Transactional
    public CrewWithMembers addMember(String sheetId, String user, long crewId, long personId) {
        sheets.lock(sheetId);
        Crew crew = crews.findBySheetIdAndId(sheetId, crewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (crew.getLeadId().equals(personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The lead is not a member of their own crew.");
        }
        if (members.existsByCrewIdAndPersonId(crewId, personId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew.");
        }
        members.saveAndFlush(new CrewMember(crewId, personId));
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
        return list(sheetId).stream().filter(c -> c.crew().getId().equals(crewId)).findFirst().orElseThrow();
    }

    /**
     * A person merged into another: the crews they lead are led by the other, and their memberships move to
     * the other, dropped where the other is already in the crew or leads it.
     */
    @Transactional
    public void merge(Person from, Person into) {
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(from.getId())) {
            crew.leadBy(into.getId());
            crews.save(crew);
            members.findByCrewIdInOrderByIdAsc(List.of(crew.getId())).stream()
                    .filter(m -> m.getPersonId().equals(into.getId())).forEach(members::delete);
        }
        crews.flush();
        for (CrewMember member : members.findByPersonIdOrderByIdAsc(from.getId())) {
            Crew crew = crews.findById(member.getCrewId()).orElseThrow();
            if (crew.getLeadId().equals(into.getId())
                    || members.existsByCrewIdAndPersonId(member.getCrewId(), into.getId())) {
                members.delete(member);
            } else {
                member.moveTo(into.getId());
                members.save(member);
            }
            members.flush();
        }
    }

    /**
     * A person leaving the sheet: their memberships go, and so do the crews they lead with their members and
     * their pools, each with its place in another pool.
     */
    @Transactional
    public void leave(Person person) {
        members.deleteAll(members.findByPersonIdOrderByIdAsc(person.getId()));
        List<Crew> led = crews.findByLeadIdOrderByIdAsc(person.getId());
        List<Pool> ledPools = pools.findByCrewIdInOrderByIdAsc(led.stream().map(Crew::getId).toList());
        ledPools.forEach(p -> poolMembers.deleteAll(poolMembers.findByMemberPoolId(p.getId())));
        poolMembers.flush();
        pools.deleteAll(ledPools);
        pools.flush();
        members.deleteAll(members.findByCrewIdInOrderByIdAsc(led.stream().map(Crew::getId).toList()));
        crews.deleteAll(led);
        members.flush();
        crews.flush();
    }
}
