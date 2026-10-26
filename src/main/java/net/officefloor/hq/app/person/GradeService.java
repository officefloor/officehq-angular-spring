package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's grades and which of them are retired. */
@Service
public class GradeService {

    private final RetiredGradeRepository retired;
    private final SheetGradeRepository added;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final PersonRepository people;
    private final PersonService persons;
    private final ObjectProvider<SettingService> settings;
    private final Rollup rollup;

    public GradeService(RetiredGradeRepository retired, SheetGradeRepository added, SheetService sheets, SheetRepository sheetRows, Audit audit,
            PersonRepository people, PersonService persons, ObjectProvider<SettingService> settings,
            Rollup rollup) {
        this.rollup = rollup;
        this.added = added;
        this.people = people;
        this.persons = persons;
        this.settings = settings;
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

    /** The sheet's grades in order: A, B and C, then those added to it. */
    @Transactional(readOnly = true)
    public List<Grade> gradesOf(String sheetId) {
        return added.gradesOf(sheetId);
    }

    /** Add a grade to the sheet: its code trimmed, refused unless one to three capital letters, and when it is
     *  already one of the sheet's grades. */
    @Transactional
    public Grade add(String sheetId, String user, String code) {
        String trimmed = code == null ? "" : code.trim();
        if (!Grade.isCode(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A grade is one to three capital letters.");
        }
        Grade grade = Grade.valueOf(trimmed);
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (added.isGradeOf(sheetId, grade)) throw alreadyAGrade(grade);
        try {
            added.saveAndFlush(new SheetGrade(sheetId, grade));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyAGrade(grade);
        }
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return grade;
    }

    /** Retire the grade on the sheet, refused when it is already retired. The people on it keep it. */
    @Transactional
    public Grade retire(String sheetId, String user, Grade grade) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (!added.isGradeOf(sheetId, grade)) throw noSuchGrade(grade);
        if (retired.existsBySheetIdAndGrade(sheetId, grade)) throw alreadyRetired(grade);
        try {
            retired.saveAndFlush(new RetiredGrade(sheetId, grade));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyRetired(grade);
        }
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + user + " grade=" + grade);
        return grade;
    }

    /** Merge a grade into another: everyone whose grade in force on the sheet's today is the merged grade
     *  is given the other from the sheet's today, in list order, each as the person form would edit them;
     *  then every grade of theirs on the merged grade, whatever its from-date, becomes the other, so all
     *  their claims use the other grade's rates, terms and thresholds.
     *  Refused unless the user is a steward of the sheet, for the same grade twice, and for a retired
     *  grade to merge into; a person who cannot be edited refuses the whole merge. After the people's
     *  own lines, the figures the merge as a whole moved. The people changed. */
    @Transactional
    public List<Person> merge(String sheetId, String user, Grade from, Grade into) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (settings.getObject().roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge grades.");
        }
        if (!added.isGradeOf(sheetId, from)) throw noSuchGrade(from);
        if (!added.isGradeOf(sheetId, into)) throw noSuchGrade(into);
        if (from == into) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different grades to merge.");
        }
        if (retired.existsBySheetIdAndGrade(sheetId, into)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Grade " + into + " is retired; choose another grade.");
        }
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Person> changed = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            if (person.gradeOn(today) != from) continue;
            Person moved = persons.grade(sheetId, user, person.getId(), person.getVersion(), into);
            // Retroactive: every grade of theirs on the merged grade becomes the other, whatever its date.
            if (moved.regrade(from, into)) moved = people.saveAndFlush(moved);
            changed.add(moved);
        }
        rollup.recordChanges(user, before);
        return changed;
    }

    private static ResponseStatusException alreadyAGrade(Grade grade) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is already a grade.");
    }

    private static ResponseStatusException noSuchGrade(Grade grade) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no grade " + grade + ".");
    }

    private static ResponseStatusException alreadyRetired(Grade grade) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is already retired.");
    }
}
