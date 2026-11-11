package net.officefloor.hq.app.grade;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The grades of a sheet (A, B and C), each of which can be retired. People on a retired grade keep
 * it, but it cannot be chosen for a new person or in an edit (see
 * {@link net.officefloor.hq.app.person.PersonService}); a retired default grade falls back to A.
 */
@Service
public class GradeService {

    /** The grades there are. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final Audit audit;

    public GradeService(SheetService sheets, SheetRepository sheetRows, RoleService roles, Audit audit) {
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.audit = audit;
    }

    /** The grades retired on the sheet, in A, B, C order. */
    @Transactional(readOnly = true)
    public List<String> retired(String sheetId) {
        return sheetRows.findById(sheetId).map(Sheet::retiredGradeList).orElse(List.of());
    }

    /** Retire the grade; refused (400) for no grade there is, and (409) for one already retired. */
    @Transactional
    public List<String> retire(String sheetId, String user, String grade) {
        roles.refuseViewer(sheetId, user);
        if (!GRADES.contains(grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a grade of A, B or C.");
        }
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
}
