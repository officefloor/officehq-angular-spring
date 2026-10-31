package net.officefloor.hq.app.rate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The grades of a sheet: A, B and C, then those added to it (a code of one to three capital letters not yet a
 * grade), in the order added; each can be retired. A retired grade cannot be chosen for a
 * person: not for a new person, nor in an edit (one leaving a person's grade as it was, with no from-date, is
 * not a choice of it), so people already on it keep it.
 */
@Service
public class GradeService {

    /** The grades every sheet has, in order. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    /** A code a grade can be added as. */
    public static final String CODE = "[A-Z]{1,3}";

    private final RetiredGradeRepository retired;
    private final AddedGradeRepository added;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;
    private final AccessService access;

    public GradeService(RetiredGradeRepository retired, AddedGradeRepository added, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events, AccessService access) {
        this.access = access;
        this.retired = retired;
        this.added = added;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The sheet's grades, in order: A, B and C, then those added, in the order added. */
    @Transactional(readOnly = true)
    public List<String> grades(String sheetId) {
        return grades(added, sheetId);
    }

    /** The sheet's grades, in order, read from the grades added to it. */
    public static List<String> grades(AddedGradeRepository added, String sheetId) {
        List<String> grades = new ArrayList<>(GRADES);
        added.findBySheetIdOrderById(sheetId).forEach(g -> grades.add(g.getGrade()));
        return grades;
    }

    @Transactional(readOnly = true)
    public boolean isGrade(String sheetId, String grade) {
        return grades(sheetId).contains(grade);
    }

    /** Refuses a grade that is not one of the sheet's. */
    public void known(String sheetId, String grade) {
        if (!isGrade(sheetId, grade)) {
            throw new GradeRefused("No such grade: " + grade + ".");
        }
    }

    /** Add a grade to the sheet: a code of one to three capital letters not yet a grade; anything else is refused. */
    @Transactional
    public void add(String sheetId, String user, String grade) {
        if (grade == null || !grade.matches(CODE)) {
            throw new GradeRefused("Enter a grade of one to three capital letters.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (isGrade(sheetId, grade)) {
            throw new GradeRefused("Grade " + grade + " is already a grade.");
        }
        added.saveAndFlush(new AddedGrade(sheetId, grade));
        events.publishEvent(new GradeAdded(sheetId, user, grade));
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

    /** Refuses a choice of the grade when it is not one of the sheet's or is retired on it. */
    public void choosable(String sheetId, String grade) {
        known(sheetId, grade);
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
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        known(sheetId, grade);
        if (isRetired(sheetId, grade)) {
            throw new GradeRefused("Grade " + grade + " is already retired.");
        }
        retired.saveAndFlush(new RetiredGrade(sheetId, grade));
        events.publishEvent(new GradeRetired(sheetId, user, grade));
    }

    /**
     * Record a steward's merge of a grade into another (the people on it are moved by the caller). Anyone but
     * a steward, a grade not on the list, the same grade twice or a retired grade to merge into is refused.
     */
    @Transactional
    public void merged(String sheetId, String user, String from, String into) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (!isGrade(sheetId, from) || !isGrade(sheetId, into)) {
            throw new GradeRefused("Choose two grades.");
        }
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new GradeRefused("Only a steward can merge grades.");
        }
        if (from.equals(into)) {
            throw new GradeRefused("A grade cannot be merged into itself.");
        }
        choosable(sheetId, into);
        events.publishEvent(new GradeMerged(sheetId, user, from, into));
    }
}
