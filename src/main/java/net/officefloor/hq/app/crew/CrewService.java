package net.officefloor.hq.app.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's crews, each name once, each led by a person of the sheet, and their members: people of the
 * sheet other than the lead, each at most once in a crew. A person can be in several crews. A crew is also a
 * pool (see the pools), so no crew has a pool's name.
 */
@Service
public class CrewService {

    /** A person in a crew as shown: their id and name. */
    public record Member(Long personId, String name) {}

    /** A crew as shown: its lead (null: no longer on the sheet) and its members in the order added. */
    public record CrewLine(Long id, String name, Member lead, List<Member> members) {}

    private final SheetRepository sheets;
    private final CrewRepository crews;
    private final CrewMemberRepository members;
    private final PersonRepository people;
    private final PoolRepository pools;
    private final RollupService rollup;
    private final Audit audit;

    public CrewService(SheetRepository sheets, CrewRepository crews, CrewMemberRepository members,
            PersonRepository people, PoolRepository pools, RollupService rollup, Audit audit) {
        this.pools = pools;
        this.rollup = rollup;
        this.sheets = sheets;
        this.crews = crews;
        this.members = members;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's crews, in the order they were added; only people still on the sheet are shown. */
    @Transactional(readOnly = true)
    public List<CrewLine> crews(String sheetId) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<CrewMember>> byCrew = members.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(CrewMember::getCrewId));
        List<CrewLine> lines = new ArrayList<>();
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            Person lead = byId.get(crew.getLeadPersonId());
            List<Member> shown = new ArrayList<>();
            for (CrewMember m : byCrew.getOrDefault(crew.getId(), List.of())) {
                Person p = byId.get(m.getPersonId());
                if (p != null) shown.add(new Member(p.getId(), p.getName()));
            }
            lines.add(new CrewLine(crew.getId(), crew.getName(),
                    lead == null ? null : new Member(lead.getId(), lead.getName()), shown));
        }
        return lines;
    }

    /** Add a crew, its name trimmed, led by a person of the sheet; refused when blank or already a crew or a pool of the sheet. */
    @Transactional
    public Crew add(String sheetId, String user, String name, Long leadId) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        String text = name == null ? "" : name.strip();
        if (text.isEmpty()) throw new CrewNameBlankException();
        Person lead = people.findByIdAndSheetId(leadId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (crews.existsBySheetIdAndName(sheetId, text) || pools.existsBySheetIdAndName(sheetId, text)) throw new DuplicateCrewNameException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Crew saved = crews.saveAndFlush(new Crew(sheetId, text, lead.getId()));
        audit.recordOnCommit("CREW_ADDED sheet=" + sheetId + " by=" + user.strip() + " name=" + text
                + " lead=" + lead.getName());
        // a crew is a pool: the cuts it moves move the capped due-totals
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Add a person of the sheet to a crew; refused when they are its lead or already in it. */
    @Transactional
    public void addMember(String sheetId, String user, Long crewId, Long personId) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Crew crew = crews.findByIdAndSheetId(crewId, sheetId).orElseThrow(CrewNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(crew.getLeadPersonId())
                || members.existsByCrewIdAndPersonId(crew.getId(), person.getId())) {
            throw new CrewMemberRefusedException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        members.saveAndFlush(new CrewMember(crew.getId(), sheetId, person.getId()));
        audit.recordOnCommit("CREW_MEMBER_ADDED sheet=" + sheetId + " by=" + user.strip() + " crew=" + crew.getName()
                + " name=" + person.getName());
        rollup.recordChanges(sheetId, user, before);
    }
}
