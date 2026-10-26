package net.officefloor.hq.app.person;

import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's grades and which of them are retired. */
@Service
public class GradeService {

    private final RetiredGradeRepository retired;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, SheetService sheets, SheetRepository sheetRows, Audit audit) {
        this.retired = retired;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The grades retired on the sheet. */
    @Transactional(readOnly = true)
    public Set<Grade> retired(String sheetId) {
        return retired.findBySheetId(sheetId).stream().map(RetiredGrade::getGrade).collect(Collectors.toSet());
    }

    /** Retire the grade on the sheet, refused when it is already retired. The people on it keep it. */
    @Transactional
    public Grade retire(String sheetId, String user, Grade grade) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (retired.existsBySheetIdAndGrade(sheetId, grade)) throw alreadyRetired(grade);
        try {
            retired.saveAndFlush(new RetiredGrade(sheetId, grade));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyRetired(grade);
        }
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return grade;
    }

    private static ResponseStatusException alreadyRetired(Grade grade) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is already retired.");
    }
}
