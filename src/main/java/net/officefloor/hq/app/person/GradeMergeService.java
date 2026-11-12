package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a grade into another: every person whose grade in force on the sheet's today is the merged grade is given the
 * other from the sheet's today, in list order, each saved as the person form would save them (keeping their name,
 * terms and contact), so each records its own PERSON_EDITED and rollup lines; then the figures the whole merge moved
 * are recorded. Only a steward may merge, and never a
 * grade into itself. One person that cannot be saved refuses the whole merge: nothing is saved.
 */
@Service
public class GradeMergeService {

    private final PersonRepository people;
    private final PersonService service;
    private final RoleService roles;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;

    public GradeMergeService(PersonRepository people, PersonService service, RoleService roles, SheetService sheets,
            Audit audit, RollupService rollup) {
        this.rollup = rollup;
        this.people = people;
        this.service = service;
        this.roles = roles;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** How many people were given the other grade. */
    public record Result(int changed) {
    }

    @Transactional
    public Result merge(String sheetId, String user, String from, String into) {
        if (!roles.isSteward(sheetId, user)) {
            throw new GradeMergeRefusedException(sheetId, from, into, user + " is not a steward");
        }
        if (from.equals(into)) {
            throw new GradeMergeRefusedException(sheetId, from, into, "a grade cannot be merged into itself");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Person> merged = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> from.equals(p.gradeOn(today)))
                .toList();
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        for (Person person : merged) {
            service.update(sheetId, user, person.getId(), null, person.getName(), into, null, person.getTerms(),
                    person.getContact());
        }
        rollup.recordChanges(sheetId, user, before);
        return new Result(merged.size());
    }
}
