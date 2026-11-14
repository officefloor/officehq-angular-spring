package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gives a selection of people a grade together. */
@Service
public class BulkGradeService {

    private final PersonRepository people;
    private final PersonService service;
    private final RollupService rollups;
    private final SettingService settings;
    private final SheetService sheets;

    public BulkGradeService(PersonRepository people, PersonService service, RollupService rollups,
            SettingService settings, SheetService sheets) {
        this.people = people;
        this.service = service;
        this.rollups = rollups;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Give each selected person, in list order (the order they were added), the grade from a date
     *  (null takes the sheet's today) exactly as saving them from the editor with their name and terms
     *  unchanged would, each followed by the figures it moved. Refused as a whole, writing nothing,
     *  when any selected person is not on the sheet. A viewer, or anyone on an archived sheet, gives
     *  no one the grade. */
    @Transactional
    public List<Person> give(String sheetId, String user, Set<Long> ids, String grade, LocalDate from) {
        List<Person> selected = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(person -> ids.contains(person.getId())).toList();
        if (selected.size() != ids.size()) {
            throw new NoSuchElementException("Not every selected person is on sheet " + sheetId);
        }
        List<Person> graded = new ArrayList<>();
        if (settings.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            // A viewer, or anyone on an archived sheet, changes nothing: no selected person is graded.
            return graded;
        }
        for (Person person : selected) {
            graded.add(rollups.track(sheetId, user, () -> service.update(sheetId, user, person.getId(),
                    person.getVersion(), person.getName(), grade, from, person.getTerms(), person.getContact())));
        }
        return graded;
    }
}
