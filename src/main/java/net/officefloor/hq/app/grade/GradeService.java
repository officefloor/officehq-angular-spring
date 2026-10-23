package net.officefloor.hq.app.grade;

import java.util.ArrayList;
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
 * The grades of a sheet, A, B and C and then those added to it in the order they were added, each of
 * which can be retired: people on a retired grade keep it, but it cannot be given to anyone else. An
 * added grade is a code of one to three capital letters not yet a grade of the sheet.
 */
@Service
public class GradeService {

    /** The grades every sheet has. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    /** What any grade is: one to three capital letters. */
    public static final String CODE = "[A-Z]{1,3}";

    private final RetiredGradeRepository retired;
    private final AddedGradeRepository added;
    private final SheetService sheets;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, AddedGradeRepository added, SheetService sheets, Audit audit) {
        this.retired = retired;
        this.added = added;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's grades: A, B and C, then those added to it in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        List<String> grades = new ArrayList<>(GRADES);
        added.findBySheetIdOrderByIdAsc(sheetId).forEach((g) -> grades.add(g.getGrade()));
        return grades;
    }

    /** Whether the grade is one of the sheet's. */
    @Transactional(readOnly = true)
    public boolean isGrade(String sheetId, String grade) {
        return grade != null && (GRADES.contains(grade) || added.existsBySheetIdAndGrade(sheetId, grade));
    }

    /** Refused unless the grade is one of the sheet's. */
    public void requireGrade(String sheetId, String grade) {
        if (!isGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no such grade.");
        }
    }

    /**
     * Add a grade to the sheet, its code trimmed: refused unless it is one to three capital letters
     * and not yet a grade of the sheet.
     */
    @Transactional
    public String add(String sheetId, String user, String code) {
        String grade = code == null ? "" : code.trim();
        if (!grade.matches(CODE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade is one to three capital letters.");
        }
        sheets.lock(sheetId);
        if (isGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already a grade.");
        }
        added.saveAndFlush(new AddedGrade(sheetId, grade, user));
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return grade;
    }

    /** The grades retired on the sheet. */
    @Transactional(readOnly = true)
    public Set<String> retired(String sheetId) {
        return retired.findBySheetId(sheetId).stream().map(RetiredGrade::getGrade).collect(Collectors.toSet());
    }

    /** Retire a grade on the sheet; refused for a grade that is not one or is already retired. */
    @Transactional
    public void retire(String sheetId, String user, String grade) {
        requireGrade(sheetId, grade);
        sheets.lock(sheetId);
        if (retired.existsBySheetIdAndGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already retired.");
        }
        retired.saveAndFlush(new RetiredGrade(sheetId, grade, user));
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
    }
}
