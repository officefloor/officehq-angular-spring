package net.officefloor.hq.app.grade;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The grades retired on a sheet. A retired grade stays with the people already on it, but cannot be
 * given to a new person or chosen in an edit (by the form, the row, a bulk grade or an import); the
 * quick-add bar, and an import line with a blank grade, fall back to A while the default grade is retired. */
@Service
public class GradeService {

    private final SheetRepository sheets;
    private final SheetGradeRetiredRepository retired;
    private final Audit audit;

    public GradeService(SheetRepository sheets, SheetGradeRetiredRepository retired, Audit audit) {
        this.sheets = sheets;
        this.retired = retired;
        this.audit = audit;
    }

    /** The sheet's retired grades, in grade order. */
    @Transactional(readOnly = true)
    public List<String> retired(String sheetId) {
        return retired.findBySheetIdOrderByGradeAsc(sheetId).stream().map(SheetGradeRetired::getGrade).toList();
    }

    @Transactional(readOnly = true)
    public boolean isRetired(String sheetId, String grade) {
        return grade != null && retired.existsBySheetIdAndGrade(sheetId, grade);
    }

    /** Refuses a retired grade. */
    @Transactional(readOnly = true)
    public void requireOpen(String sheetId, String grade) {
        if (isRetired(sheetId, grade)) throw new GradeRetiredException();
    }

    /** The grade a person added without one is given: the sheet's default grade, or A while it is retired. */
    @Transactional(readOnly = true)
    public String defaultGradeFor(String sheetId, String defaultGrade) {
        return isRetired(sheetId, defaultGrade) ? SettingsService.DEFAULT_GRADE : defaultGrade;
    }

    /** Retires {@code grade}; refused when it is already retired. */
    @Transactional
    public void retire(String sheetId, String by, String grade) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        requireOpen(sheetId, grade);
        retired.saveAndFlush(new SheetGradeRetired(sheetId, grade));
        audit.change("GRADE_RETIRED", sheetId, by, "grade=" + grade);
    }
}
