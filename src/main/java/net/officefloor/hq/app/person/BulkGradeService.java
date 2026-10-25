package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Gives selected people a grade together. Each is edited on its own, as the person form would edit
 *  them, keeping their name and terms, so one that cannot be changed is left as it is and the others
 *  still change. */
@Service
public class BulkGradeService {

    private final PersonService service;
    private final PersonRepository people;

    public BulkGradeService(PersonService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** Give the people of these ids the grade from the from-date (none: the sheet's today), in list
     *  order: the order they were added. An id not of the sheet is left out. The people changed, in
     *  the order they were changed. */
    public List<Person> grade(String sheetId, String user, Collection<Long> ids, Grade grade, LocalDate from) {
        Set<Long> selected = new LinkedHashSet<>(ids);
        List<Person> changed = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!selected.contains(person.getId())) continue;
            try {
                changed.add(service.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        grade, from, person.getTerms()));
            } catch (ResponseStatusException notChanged) {
                // Left as it is: someone else saved the person at the same moment.
            }
        }
        return changed;
    }
}
