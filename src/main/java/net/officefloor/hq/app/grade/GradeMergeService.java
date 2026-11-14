package net.officefloor.hq.app.grade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merges one grade into another: everyone on the merged grade today is given the other. */
@Service
public class GradeMergeService {

    private final PersonRepository people;
    private final PersonService service;
    private final PersonGradeService grades;
    private final GradeService gradeRules;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;
    private final Audit audit;

    public GradeMergeService(PersonRepository people, PersonService service, PersonGradeService grades,
            GradeService gradeRules, RollupService rollups, SettingService settings, SheetService sheets, Audit audit) {
        this.people = people;
        this.service = service;
        this.grades = grades;
        this.gradeRules = gradeRules;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** A steward merges a grade into another: every person whose grade in force on the sheet's today
     *  is the merged grade is given the other from the sheet's today, in list order, exactly as saving
     *  them from the editor with their name and terms unchanged would, each followed by the figures it
     *  moved, and the merge as a whole is followed by the figures it moved. Anyone but a steward, the same grade twice, or a grade that is not one, is refused, as is
     *  the merge as a whole when any person cannot be given the grade (a retired one). Returns the
     *  people given the grade, in that order. */
    @Transactional
    public List<Person> merge(String sheetId, String user, String from, String into) {
        if (!gradeRules.isGrade(sheetId, from) || !gradeRules.isGrade(sheetId, into)) {
            throw new GradeMergeRefusedException("There is no grade " + from + " or " + into);
        }
        if (from.equals(into)) {
            throw new GradeMergeRefusedException("A grade cannot be merged into itself");
        }
        sheets.lock(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new GradeMergeRefusedException(user + " is not a steward: only a steward merges grades");
        }
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        LocalDate today = sheets.today(sheetId);
        return rollups.track(sheetId, user, () -> {
            List<Person> merged = new ArrayList<>();
            for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
                if (!from.equals(grades.gradeOn(person.getId(), today))) {
                    continue;
                }
                merged.add(rollups.track(sheetId, user, () -> service.update(sheetId, user, person.getId(),
                        person.getVersion(), person.getName(), into, today, service.termsOn(person, today),
                        person.getContact())));
            }
            return merged;
        });
    }
}
