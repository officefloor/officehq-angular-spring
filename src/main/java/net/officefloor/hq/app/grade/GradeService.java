package net.officefloor.hq.app.grade;

import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The grades of a sheet and which of them are retired. */
@Service
public class GradeService {

    /** A grade's code: one to three capital letters. */
    public static final Pattern CODE = Pattern.compile("[A-Z]{1,3}");

    private final RetiredGradeRepository retired;
    private final SheetGradeRepository added;
    private final SheetService sheets;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, SheetGradeRepository added, SheetService sheets, Audit audit) {
        this.retired = retired;
        this.added = added;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Every grade of the sheet: A, B and C, then those added to it in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        return sheets.grades(sheetId);
    }

    /** Whether the grade is one of the sheet's. */
    @Transactional(readOnly = true)
    public boolean isGrade(String sheetId, String grade) {
        return sheets.isGrade(sheetId, grade);
    }

    /** Add a grade to the sheet; refused unless it is one to three capital letters not yet a grade
     *  of it. It changes no one. */
    @Transactional
    public synchronized SheetGrade add(String sheetId, String user, String grade) {
        if (grade == null || !CODE.matcher(grade).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade is one to three capital letters");
        }
        sheets.load(sheetId, user);
        if (added.isGradeOf(sheetId, grade)) {
            throw alreadyAGrade();
        }
        SheetGrade saved;
        try {
            saved = added.saveAndFlush(new SheetGrade(sheetId, grade, sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyAGrade();
        }
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return saved;
    }

    private static ResponseStatusException alreadyAGrade() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already a grade of the sheet");
    }

    /** The grades retired on the sheet, by grade. */
    @Transactional(readOnly = true)
    public List<RetiredGrade> retired(String sheetId) {
        return retired.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Whether the grade is retired on the sheet. */
    @Transactional(readOnly = true)
    public boolean isRetired(String sheetId, String grade) {
        return retired.existsBySheetIdAndGrade(sheetId, grade);
    }

    /** Refuse the grade as a choice for a person when it is no grade of the sheet or is retired on it. */
    @Transactional(readOnly = true)
    public void checkChoosable(String sheetId, String grade) {
        if (!isGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grade " + grade + " is no grade of the sheet");
        }
        if (isRetired(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grade " + grade + " is retired");
        }
    }

    /** Retire one of the grades on the sheet; refused when it is no grade or is retired already. It
     *  changes no one: people on it keep it. */
    @Transactional
    public synchronized RetiredGrade retire(String sheetId, String user, String grade) {
        if (!isGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such grade on the sheet");
        }
        sheets.load(sheetId, user);
        if (retired.existsBySheetIdAndGrade(sheetId, grade)) {
            throw alreadyRetired();
        }
        RetiredGrade saved;
        try {
            saved = retired.saveAndFlush(new RetiredGrade(sheetId, grade, sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyRetired();
        }
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return saved;
    }

    private static ResponseStatusException alreadyRetired() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That grade is already retired");
    }
}
