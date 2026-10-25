package net.officefloor.hq.app.crew;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.SheetPoolRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The crews on a sheet: each a name, unique on the sheet, a lead chosen from the sheet's people, and the
 * members added to it (never the lead, each at most once). A crew's due-total adds up the lead's and the
 * members' due-totals as the sheet shows them, so it is worked out where those are: on the screen. A crew is
 * also one of the sheet's pools (see the pools), so its name is never a pool's. */
@Service
public class CrewService {

    /** A person in a crew as shown: their id and name now. */
    public record Member(long personId, String name) {
    }

    /** A crew as shown: its lead (null once the lead has left the sheet) and its members in the order they were added. */
    public record Crewed(SheetCrew crew, Member lead, List<Member> members) {
    }

    private final SheetRepository sheets;
    private final SheetCrewRepository crews;
    private final SheetCrewMemberRepository members;
    private final SheetPoolRepository pools;
    private final PersonRepository people;
    private final Audit audit;

    public CrewService(SheetRepository sheets, SheetCrewRepository crews, SheetCrewMemberRepository members,
            SheetPoolRepository pools, PersonRepository people, Audit audit) {
        this.sheets = sheets;
        this.crews = crews;
        this.members = members;
        this.pools = pools;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's crews, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Crewed> crews(String sheetId) {
        List<SheetCrew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) return List.of();
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<Member>> byCrew = new LinkedHashMap<>();
        for (SheetCrewMember m : members.findByCrewIdInOrderByIdAsc(all.stream().map(SheetCrew::getId).toList())) {
            Person person = byId.get(m.getPersonId());
            if (person == null) continue;
            byCrew.computeIfAbsent(m.getCrewId(), k -> new ArrayList<>())
                    .add(new Member(person.getId(), person.getName()));
        }
        List<Crewed> found = new ArrayList<>();
        for (SheetCrew crew : all) {
            Person lead = crew.getLeadPersonId() == null ? null : byId.get(crew.getLeadPersonId());
            found.add(new Crewed(crew, lead == null ? null : new Member(lead.getId(), lead.getName()),
                    byCrew.getOrDefault(crew.getId(), List.of())));
        }
        return found;
    }

    /** Add a crew named {@code name} (trimmed, not blank) led by the person; refused when the sheet has one by
     * that name or a pool by that name. */
    @Transactional
    public SheetCrew add(String sheetId, String by, String name, long leadId) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (name.isEmpty()) throw new CrewBlankException();
        Person lead = people.findByIdAndSheetId(leadId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (crews.existsBySheetIdAndName(sheetId, name) || pools.existsBySheetIdAndName(sheetId, name)) {
            throw new CrewTakenException();
        }
        SheetCrew added = crews.saveAndFlush(new SheetCrew(sheetId, name, lead.getId()));
        audit.change("CREW_ADDED", sheetId, by, "name=" + name, "lead=" + lead.getName());
        return added;
    }

    /** Add the person to the crew; refused when they are its lead or already in it. */
    @Transactional
    public SheetCrewMember addMember(String sheetId, String by, long crewId, long personId) {
        SheetCrew crew = crews.findByIdAndSheetId(crewId, sheetId).orElseThrow(CrewNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(crew.getLeadPersonId())
                || members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new CrewMemberTakenException();
        }
        SheetCrewMember added = members.saveAndFlush(new SheetCrewMember(crew.getId(), person.getId()));
        audit.change("CREW_MEMBER_ADDED", sheetId, by, "crew=" + crew.getName(), "name=" + person.getName());
        return added;
    }

    /** The person leaves the sheet: their memberships are dropped, and each crew they lead is kept, with its
     * members, but no lead. */
    @Transactional
    public void personLeaving(long personId) {
        members.deleteAll(members.findByPersonIdOrderByIdAsc(personId));
        members.flush();
        for (SheetCrew crew : crews.findByLeadPersonIdOrderByIdAsc(personId)) {
            crew.setLeadPersonId(null);
        }
        crews.flush();
    }

    /** The person {@code fromId} is merged into {@code intoId}: the crews they lead are led by the other,
     * except one the other is already a member of, which keeps them as a member and is left without a lead;
     * and their memberships move to the other where the other is neither the lead nor already a member. */
    @Transactional
    public void personMerged(long fromId, long intoId) {
        for (SheetCrew crew : crews.findByLeadPersonIdOrderByIdAsc(fromId)) {
            crew.setLeadPersonId(members.existsByCrewIdAndPersonId(crew.getId(), intoId) ? null : intoId);
        }
        crews.flush();
        members.flush();
        for (SheetCrewMember m : members.findByPersonIdOrderByIdAsc(fromId)) {
            SheetCrew crew = crews.findById(m.getCrewId()).orElseThrow();
            if (Long.valueOf(intoId).equals(crew.getLeadPersonId()) || members.existsByCrewIdAndPersonId(crew.getId(), intoId)) {
                members.delete(m);
            } else {
                m.setPersonId(intoId);
            }
            members.flush();
        }
    }

    /** Copy the sheet's crews to the clone, with the people's ids there. */
    @Transactional
    public void cloneTo(String sheetId, String toSheetId, Map<Long, Long> personIds) {
        List<SheetCrew> all = crews.findBySheetIdOrderByIdAsc(sheetId);
        if (all.isEmpty()) return;
        Map<Long, Long> crewIds = new HashMap<>();
        for (SheetCrew crew : all) {
            crewIds.put(crew.getId(), crews.saveAndFlush(
                    new SheetCrew(toSheetId, crew.getName(), crew.getLeadPersonId() == null ? null : personIds.get(crew.getLeadPersonId()))).getId());
        }
        members.findByCrewIdInOrderByIdAsc(crewIds.keySet()).forEach(m -> members.save(
                new SheetCrewMember(crewIds.get(m.getCrewId()), personIds.get(m.getPersonId()))));
        members.flush();
    }
}
