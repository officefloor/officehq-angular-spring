package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The grades of a sheet, A, B and C, each of which can be retired. A retired grade cannot be chosen for a
 * person: not for a new person, nor in an edit (one leaving a person's grade as it was, with no from-date, is
 * not a choice of it), so people already on it keep it.
 */
@Service
public class GradeService {

    /** Every grade, in order. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    private final RetiredGradeRepository retired;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public GradeService(RetiredGradeRepository retired, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.retired = retired;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The sheet's retired grades. */
    @Transactional(readOnly = true)
    public Set<String> retired(String sheetId) {
        return retired.findBySheetId(sheetId).stream().map(RetiredGrade::getGrade).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public boolean isRetired(String sheetId, String grade) {
        return retired.existsBySheetIdAndGrade(sheetId, grade);
    }

    /** Refuses a choice of the grade when it is retired on the sheet. */
    public void choosable(String sheetId, String grade) {
        if (isRetired(sheetId, grade)) {
            throw new GradeRefused("Grade " + grade + " is retired: it cannot be chosen.");
        }
    }

    /** The grade to give by default: the sheet's default grade (A when none is set or it is retired). */
    public String defaultGrade(String sheetId) {
        String grade = sheets.settings(sheetId).defaultGrade();
        return isRetired(sheetId, grade) ? "A" : grade;
    }

    /** Retire the grade; a grade not on the sheet's list or already retired is refused. */
    @Transactional
    public void retire(String sheetId, String user, String grade) {
        if (!GRADES.contains(grade)) {
            throw new GradeRefused("No such grade: " + grade + ".");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (isRetired(sheetId, grade)) {
            throw new GradeRefused("Grade " + grade + " is already retired.");
        }
        retired.saveAndFlush(new RetiredGrade(sheetId, grade));
        events.publishEvent(new GradeRetired(sheetId, user, grade));
    }
}
