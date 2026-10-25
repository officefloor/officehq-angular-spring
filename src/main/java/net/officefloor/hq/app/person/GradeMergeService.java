package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A grade merged into another: every person whose grade in force on the sheet's today is the merged
 * grade is given the other from the sheet's today, in list order, each through {@link PersonService#grade}
 * exactly as the person form saves them, so each records its own PERSON_EDITED and rollup lines after the
 * GRADE_MERGED line; the figures the merge as a whole moved are recorded after them. Only a steward may merge, and not a grade into itself nor into a retired grade. */
@Service
public class GradeMergeService {

    private final PersonService service;
    private final PersonRepository people;
    private final SettingsService settings;
    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final GradeService grades;
    private final Audit audit;
    private final RollupService rollup;

    public GradeMergeService(PersonService service, PersonRepository people, SettingsService settings,
            SheetRepository sheets, SheetService sheetService, GradeService grades, Audit audit,
            RollupService rollup) {
        this.rollup = rollup;
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.grades = grades;
        this.audit = audit;
    }

    /** Merges grade {@code from} into {@code into}; the people it gave the other grade, in list order. */
    @Transactional
    public List<Person> merge(String sheetId, String user, String from, String into) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD || from.equals(into) || grades.isRetired(sheetId, into)) {
            throw new GradeMergeException();
        }
        LocalDate today = sheetService.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        audit.change("GRADE_MERGED", sheetId, user, "from=" + from, "into=" + into);
        List<Person> changed = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (!from.equals(person.gradeOn(today))) continue;
            changed.add(service.grade(sheetId, user, person.getId(), person.getVersion(), into));
        }
        rollup.recordSince(sheetId, user, before);
        return changed;
    }
}
