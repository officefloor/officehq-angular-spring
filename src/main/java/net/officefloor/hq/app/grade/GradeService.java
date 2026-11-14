package net.officefloor.hq.app.grade;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The grades a person may be given on a sheet: A, B and C, less those retired there. A person on a
 *  retired grade keeps it, but no one can be given it any more. */
@Service
public class GradeService {

    /** Every grade, in order. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    private final RetiredGradeRepository retired;
    private final SheetService sheets;
    private final Audit audit;

    public GradeService(RetiredGradeRepository retired, SheetService sheets, Audit audit) {
        this.retired = retired;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's retired grades. */
    public Set<String> retired(String sheetId) {
        return retired.findBySheetIdOrderByGradeAsc(sheetId).stream().map(RetiredGrade::getGrade)
                .collect(Collectors.toSet());
    }

    /** Whether the grade is retired on the sheet. */
    public boolean isRetired(String sheetId, String grade) {
        return retired.existsBySheetIdAndGrade(sheetId, grade);
    }

    /** Refuse a retired grade chosen for a person. */
    public void requireChoosable(String sheetId, String grade) {
        if (isRetired(sheetId, grade)) {
            throw new GradeRetiredException("The grade " + grade + " is retired");
        }
    }

    /** Retire a grade on the sheet. Refused when it is not a grade or is retired already; the sheet
     *  is held locked so the same grade retired at the same moment is checked against itself. */
    @Transactional
    public void retire(String sheetId, String user, String grade) {
        if (!GRADES.contains(grade)) {
            throw new GradeRetiredException("There is no grade " + grade);
        }
        sheets.lock(sheetId);
        if (isRetired(sheetId, grade)) {
            throw new GradeRetiredException("The grade " + grade + " is retired already");
        }
        retired.saveAndFlush(new RetiredGrade(sheetId, grade));
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
    }
}
