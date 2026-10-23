package net.officefloor.hq.app.grade;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The grades of a sheet, A, B and C, each of which can be retired: people on a retired grade keep it,
 * but it cannot be given to anyone else.
 */
@Service
public class GradeService {

    public static final List<String> GRADES = List.of("A", "B", "C");

    private final RetiredGradeRepository retired;
    private final SheetService sheets;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, SheetService sheets, Audit audit) {
        this.retired = retired;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The grades retired on the sheet. */
    @Transactional(readOnly = true)
    public Set<String> retired(String sheetId) {
        return retired.findBySheetId(sheetId).stream().map(RetiredGrade::getGrade).collect(Collectors.toSet());
    }

    /** Retire a grade on the sheet; refused for a grade that is not one or is already retired. */
    @Transactional
    public void retire(String sheetId, String user, String grade) {
        if (!GRADES.contains(grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no such grade.");
        }
        sheets.lock(sheetId);
        if (retired.existsBySheetIdAndGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already retired.");
        }
        retired.saveAndFlush(new RetiredGrade(sheetId, grade, user));
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
    }
}
