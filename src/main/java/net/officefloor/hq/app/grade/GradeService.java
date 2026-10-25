package net.officefloor.hq.app.grade;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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

    /** The grades every sheet has. */
    public static final List<String> BUILT_IN = List.of("A", "B", "C");

    /** A grade's code: one to three capital letters. */
    public static final Pattern CODE = Pattern.compile("[A-Z]{1,3}");

    private final SheetRepository sheets;
    private final SheetGradeRetiredRepository retired;
    private final SheetGradeRepository added;
    private final Audit audit;

    public GradeService(SheetRepository sheets, SheetGradeRetiredRepository retired, SheetGradeRepository added,
            Audit audit) {
        this.sheets = sheets;
        this.retired = retired;
        this.added = added;
        this.audit = audit;
    }

    /** The sheet's grades: A, B and C, then those added to it in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        List<String> all = new ArrayList<>(BUILT_IN);
        added.findBySheetIdOrderByIdAsc(sheetId).forEach(g -> all.add(g.getGrade()));
        return all;
    }

    /** Whether {@code grade} is one of the sheet's grades. */
    @Transactional(readOnly = true)
    public boolean isGrade(String sheetId, String grade) {
        return grade != null && (BUILT_IN.contains(grade) || added.existsBySheetIdAndGrade(sheetId, grade));
    }

    /** Refuses a grade that is not one of the sheet's. */
    @Transactional(readOnly = true)
    public void requireGrade(String sheetId, String grade) {
        if (!isGrade(sheetId, grade)) throw new GradeUnknownException();
    }

    /** Adds {@code grade} to the sheet; refused unless it is a code of one to three capital letters not
     * yet one of its grades. */
    @Transactional
    public void add(String sheetId, String by, String grade) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (grade == null || !CODE.matcher(grade).matches() || isGrade(sheetId, grade)) {
            throw new GradeUnknownException();
        }
        added.saveAndFlush(new SheetGrade(sheetId, grade));
        audit.change("GRADE_ADDED", sheetId, by, "grade=" + grade);
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

    /** Refuses a grade that is not one of the sheet's, or is retired. */
    @Transactional(readOnly = true)
    public void requireOpen(String sheetId, String grade) {
        requireGrade(sheetId, grade);
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
