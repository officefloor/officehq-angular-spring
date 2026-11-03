package net.officefloor.hq.app.grade;

import java.util.List;
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

    /** The grades a person can have. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    private final RetiredGradeRepository retired;
    private final SheetService sheets;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, SheetService sheets, Audit audit) {
        this.retired = retired;
        this.sheets = sheets;
        this.audit = audit;
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

    /** Refuse the grade as a choice for a person when it is retired on the sheet. */
    @Transactional(readOnly = true)
    public void checkChoosable(String sheetId, String grade) {
        if (isRetired(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grade " + grade + " is retired");
        }
    }

    /** Retire one of the grades on the sheet; refused when it is no grade or is retired already. It
     *  changes no one: people on it keep it. */
    @Transactional
    public synchronized RetiredGrade retire(String sheetId, String user, String grade) {
        if (!GRADES.contains(grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade is A, B or C");
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
