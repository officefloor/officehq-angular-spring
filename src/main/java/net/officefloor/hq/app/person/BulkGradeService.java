package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Gives selected people a grade together. Each is edited on its own, as the person form would edit
 *  them, keeping their name and terms, so one that cannot be changed is left as it is and the others
 *  still change. */
@Service
public class BulkGradeService {

    private final PersonService service;
    private final PersonRepository people;
    private final SettingService settings;
    private final SheetRepository sheets;

    public BulkGradeService(PersonService service, PersonRepository people, SettingService settings,
            SheetRepository sheets) {
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Give the people of these ids the grade from the from-date (none: the sheet's today), in list
     *  order: the order they were added. An id not of the sheet is left out. The people changed, in
     *  the order they were changed. A viewer changes no one, nor does anyone on an archived sheet. */
    public List<Person> grade(String sheetId, String user, Collection<Long> ids, Grade grade, LocalDate from) {
        if (settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId)) return List.of();
        Set<Long> selected = new LinkedHashSet<>(ids);
        List<Person> changed = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            if (!selected.contains(person.getId())) continue;
            try {
                changed.add(service.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        grade, from, person.getTerms(), person.getContact(), String.join(",", person.getTags()), true));
            } catch (ResponseStatusException notChanged) {
                // Left as it is: someone else saved the person at the same moment.
            }
        }
        return changed;
    }
}
