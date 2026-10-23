package net.officefloor.hq.app.grade;

import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merging a grade into another: every person whose grade in force on the sheet's today is the merged
 * grade is given the other from the sheet's today, each edited as the person form would, in list order,
 * and then every grade record of theirs on the merged grade, whatever its date, becomes the other; then
 * the figures the whole merge moved. A steward only; refused for the same grade twice. Should any edit be refused, nothing is merged.
 */
@Service
public class GradeMergeService {

    private final PersonService service;
    private final PersonRepository people;
    private final RoleService roles;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;
    private final GradeService grades;

    public GradeMergeService(PersonService service, PersonRepository people, RoleService roles, SheetService sheets,
            Rollups rollups, Audit audit, GradeService grades) {
        this.grades = grades;
        this.service = service;
        this.people = people;
        this.roles = roles;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** Merge the grade from into the grade into: the number of people given it. */
    @Transactional
    public int merge(String sheetId, String user, String from, String into) {
        if (!grades.isGrade(sheetId, from) || !grades.isGrade(sheetId, into)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no such grade.");
        }
        if (from.equals(into)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade cannot be merged into itself.");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward merges grades.");
        }
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        LocalDate today = sheets.today(sheetId);
        int merged = 0;
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (!from.equals(person.gradeOn(today))) {
                continue;
            }
            service.edit(sheetId, user, person.getId(), person.getVersion(), person.getName(), into, null,
                    person.getTerms(), person.getContact(), false, false);
            // Retroactive: every grade record of theirs on the merged grade becomes the other.
            people.findById(person.getId()).ifPresent(moved -> {
                moved.regrade(from, into);
                people.saveAndFlush(moved);
            });
            merged++;
        }
        rollups.audit(sheetId, user, before);
        return merged;
    }
}
