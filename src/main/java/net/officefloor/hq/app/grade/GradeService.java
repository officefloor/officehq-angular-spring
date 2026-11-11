package net.officefloor.hq.app.grade;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The grades of a sheet (A, B and C, and any added: a code of one to three capital letters not yet a
 * grade), each of which can be retired. People on a retired grade keep
 * it, but it cannot be chosen for a new person or in an edit (see
 * {@link net.officefloor.hq.app.person.PersonService}); a retired default grade falls back to A.
 * A steward can merge a grade into another: everyone whose grade in force on the sheet's today is
 * the merged grade is given the other from the sheet's today, in list order, exactly as the person
 * form would; then every grade record of theirs on the merged grade becomes the other, so all their
 * claims, whatever their date, follow the other grade (figures frozen or bound by a statement are kept).
 */
@Service
public class GradeService {

    /** What a grade's code is: one to three capital letters. */
    public static final String CODE = "[A-Z]{1,3}";

    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final Audit audit;
    private final PersonService people;
    private final RollupService rollup;

    public GradeService(SheetService sheets, SheetRepository sheetRows, RoleService roles, Audit audit,
            PersonService people, RollupService rollup) {
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.audit = audit;
    }

    /** The sheet's grades, in alphabetical order. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        return sheets.grades(sheetId);
    }

    /** The grades retired on the sheet, in A, B, C order. */
    @Transactional(readOnly = true)
    public List<String> retired(String sheetId) {
        return sheetRows.findById(sheetId).map(Sheet::retiredGradeList).orElse(List.of());
    }

    /**
     * Add the grade to the sheet; refused (400) for a code that is not one to three capital letters,
     * and (409) for one already a grade. Answers the sheet's grades.
     */
    @Transactional
    public List<String> add(String sheetId, String user, String grade) {
        roles.refuseViewer(sheetId, user);
        if (!grade.matches(CODE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade is one to three capital letters.");
        }
        sheets.load(sheetId, user);
        Sheet sheet = sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        if (sheet.isGrade(grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already a grade.");
        }
        sheet.addGrade(grade);
        sheetRows.saveAndFlush(sheet);
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return sheet.gradeList();
    }

    /** Retire the grade; refused (400) for no grade there is, and (409) for one already retired. */
    @Transactional
    public List<String> retire(String sheetId, String user, String grade) {
        roles.refuseViewer(sheetId, user);
        sheets.refuseUnknownGrade(sheetId, grade);
        sheets.load(sheetId, user);
        Sheet sheet = sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        if (sheet.isRetiredGrade(grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already retired.");
        }
        sheet.retireGrade(grade);
        sheetRows.saveAndFlush(sheet);
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return sheet.retiredGradeList();
    }

    /**
     * Merge the grade {@code from} into {@code into}: each person whose grade in force on the sheet's
     * today is {@code from} is edited, in list order, to {@code into} from the sheet's today, as the
     * person form would (name, terms and contact kept), and then every grade of theirs recorded as
     * {@code from}, whatever its from-date, becomes {@code into}. Refused (403) to anyone but a steward, and
     * (400) for no grade there is or the same grade twice. Answers how many people were given the grade.
     */
    @Transactional
    public int merge(String sheetId, String user, String from, String into) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        sheets.refuseUnknownGrade(sheetId, from);
        sheets.refuseUnknownGrade(sheetId, into);
        if (from.equals(into)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different grades.");
        }
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        LocalDate today = sheets.today(sheetId);
        List<Person> merged = people.list(sheetId).stream().filter(p -> from.equals(p.gradeOn(today))).toList();
        for (Person p : merged) {
            people.update(sheetId, user, p.getId(), p.getVersion(), p.getName(), into, today, p.termsOn(today),
                    p.getContact());
            people.regrade(sheetId, p.getId(), from, into);
        }
        rollup.recordChanges(sheetId, user, before);
        return merged.size();
    }
}
