package net.officefloor.hq.app.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's crews: each a name (trimmed, not blank, at most once on the sheet) and a lead chosen
 * from the sheet's people, and its members, each a person on the sheet at most once per crew and
 * never the crew's lead. A crew's due-total (the lead's and the members' due-totals added up, n/a
 * skipped) is worked out on screen from the figures it shows for each person. A crew is also listed
 * among the pools (see {@link net.officefloor.hq.app.pool.PoolService}), so it may not take a pool's name.
 */
@Service
public class CrewService {

    /** A member as listed: the person's id and current name. */
    public record MemberView(long personId, String name) {
    }

    /** A crew as listed: its lead (id and current name) and its members in the order they were added. */
    public record CrewView(long id, String name, Long leadId, String lead, List<MemberView> members) {
    }

    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;
    private final PoolRepository pools;

    public CrewService(CrewRepository crews, CrewMemberRepository members, PersonRepository people,
            SheetService sheets, SheetRepository sheetRows, Audit audit, RoleService roles, PoolRepository pools) {
        this.pools = pools;
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<CrewView> list(String sheetId) {
        List<Crew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = members
                .findByCrewIdInOrderByIdAsc(all.stream().map(Crew::getId).toList()).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        List<CrewView> out = new ArrayList<>();
        for (Crew crew : all) {
            List<MemberView> views = new ArrayList<>();
            for (CrewMember m : byCrew.getOrDefault(crew.getId(), List.of())) {
                Person p = byId.get(m.getPersonId());
                if (p != null) {
                    views.add(new MemberView(p.getId(), p.getName()));
                }
            }
            Person lead = byId.get(crew.getLeadId());
            out.add(new CrewView(crew.getId(), crew.getName(), crew.getLeadId(), lead == null ? "" : lead.getName(),
                    views));
        }
        return out;
    }

    /**
     * Add a crew (its name trimmed) led by a person on the sheet; a blank name or no lead is refused
     * (422), a name already on the sheet as a crew or a pool (409).
     */
    @Transactional
    public void add(String sheetId, String user, String name, Long leadId) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Enter a crew name.");
        }
        if (leadId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a lead.");
        }
        // hold the sheet so the same name added twice at the same moment is checked one after the other
        sheetRows.findLockedById(sheetId);
        Person lead = people.findByIdAndSheetId(leadId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a lead."));
        if (crews.existsBySheetIdAndName(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That crew is already on the sheet.");
        }
        if (pools.existsBySheetIdAndName(sheetId, text)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pool on the sheet has that name.");
        }
        try {
            crews.saveAndFlush(new Crew(sheetId, text, lead.getId()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That crew is already on the sheet.");
        }
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user + " name=" + text + " lead="
                + lead.getName());
    }

    /** Add a person to a crew: refused (409) for the crew's lead or a person already in the crew. */
    @Transactional
    public void addMember(String sheetId, String user, long crewId, Long personId) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        if (personId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a person.");
        }
        sheetRows.findLockedById(sheetId);
        Crew crew = crews.findByIdAndSheetId(crewId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such crew."));
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a person."));
        if (person.getId().equals(crew.getLeadId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person leads the crew.");
        }
        if (members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew.");
        }
        try {
            members.saveAndFlush(new CrewMember(sheetId, crew.getId(), person.getId()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already in the crew.");
        }
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user + " crew=" + crew.getName()
                + " name=" + person.getName());
    }

    /**
     * A person merged into another: the crews they lead pass to the other, and their memberships move
     * to the other, dropped where the other already is in the crew or leads it.
     */
    @Transactional
    public void merge(long fromId, long intoId) {
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(fromId)) {
            crew.setLeadId(intoId);
            crews.save(crew);
            members.findByPersonIdOrderByIdAsc(intoId).stream().filter(m -> m.getCrewId().equals(crew.getId()))
                    .forEach(members::delete);
        }
        crews.flush();
        members.flush();
        for (CrewMember member : members.findByPersonIdOrderByIdAsc(fromId)) {
            Crew crew = crews.findById(member.getCrewId()).orElse(null);
            if (crew == null || Long.valueOf(intoId).equals(crew.getLeadId())
                    || members.existsByCrewIdAndPersonId(member.getCrewId(), intoId)) {
                members.delete(member);
            } else {
                member.setPersonId(intoId);
                members.save(member);
            }
        }
        members.flush();
    }

    /** A person leaving the sheet: their memberships go, and the crews they lead are left without a lead. */
    @Transactional
    public void drop(long personId) {
        members.deleteAll(members.findByPersonIdOrderByIdAsc(personId));
        members.flush();
        for (Crew crew : crews.findByLeadIdOrderByIdAsc(personId)) {
            crew.setLeadId(null);
            crews.save(crew);
        }
        crews.flush();
    }
}
