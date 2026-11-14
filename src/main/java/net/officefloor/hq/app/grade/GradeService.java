package net.officefloor.hq.app.grade;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The grades a person may be given on a sheet: A, B and C and those added there, less those retired
 *  there. A person on a retired grade keeps it, but no one can be given it any more. */
@Service
public class GradeService {

    /** The grades every sheet has, in order. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    /** What a grade looks like: a code of one to three capital letters. */
    public static final String CODE = "[A-Z]{1,3}";

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

    /** The sheet's grades, in order: A, B and C, then those added there in the order they were added. */
    public List<String> grades(String sheetId) {
        return Stream.concat(GRADES.stream(), added.findBySheetIdOrderByIdAsc(sheetId).stream().map(SheetGrade::getGrade))
                .toList();
    }

    /** Whether the grade is one of the sheet's. */
    public boolean isGrade(String sheetId, String grade) {
        return grade != null && (GRADES.contains(grade) || added.existsBySheetIdAndGrade(sheetId, grade));
    }

    /** Add a grade on the sheet. Refused when it is not a code of one to three capital letters or is a
     *  grade already; the sheet is held locked so the same grade added at the same moment is checked
     *  against itself. */
    @Transactional
    public void add(String sheetId, String user, String grade) {
        if (grade == null || !grade.matches(CODE)) {
            throw new GradeRetiredException("A grade is one to three capital letters, not " + grade);
        }
        sheets.lock(sheetId);
        if (isGrade(sheetId, grade)) {
            throw new GradeRetiredException("The grade " + grade + " is a grade already");
        }
        added.saveAndFlush(new SheetGrade(sheetId, grade));
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user + " grade=" + grade);
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

    /** Refuse a grade chosen for a person that is not one of the sheet's, or is retired. */
    public void requireChoosable(String sheetId, String grade) {
        if (!isGrade(sheetId, grade)) {
            throw new GradeRetiredException("There is no grade " + grade);
        }
        if (isRetired(sheetId, grade)) {
            throw new GradeRetiredException("The grade " + grade + " is retired");
        }
    }

    /** Retire a grade on the sheet. Refused when it is not a grade or is retired already; the sheet
     *  is held locked so the same grade retired at the same moment is checked against itself. */
    @Transactional
    public void retire(String sheetId, String user, String grade) {
        if (!isGrade(sheetId, grade)) {
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
