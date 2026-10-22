package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Giving the selected people a grade together, from a date (null: the sheet's today). Each is edited
 * on its own, as the person form would, keeping their name and own terms, in list order: the people in
 * the order they were added. A person who cannot be edited (or is not on the sheet) is left as they
 * are, writing nothing; the others are still edited.
 */
@Service
public class BulkGradeService {

    private final PersonService service;
    private final PersonRepository people;

    public BulkGradeService(PersonService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** Give the selected people the grade from the date: the people changed, in the order they were changed. */
    public List<Person> grade(String sheetId, String user, Collection<Long> personIds, String grade, LocalDate from) {
        Set<Long> selected = new HashSet<>(personIds);
        List<Person> changed = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!selected.contains(person.getId())) {
                continue;
            }
            try {
                changed.add(service.edit(sheetId, user, person.getId(), person.getVersion(), person.getName(), grade,
                        from, person.getTerms()));
            } catch (ResponseStatusException refused) {
                // left as they are: their edit wrote nothing
            }
        }
        return changed;
    }
}
