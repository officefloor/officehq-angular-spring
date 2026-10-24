package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** A grade given to several people at once. Each person is saved in their own transaction, through
 * {@link PersonService#update}, exactly as the person form saves them (keeping their name and terms),
 * so each records its own PERSON_EDITED and rollup lines. */
@Service
public class PersonBulkService {

    private final PersonService service;
    private final PersonRepository people;

    public PersonBulkService(PersonService service, PersonRepository people) {
        this.service = service;
        this.people = people;
    }

    /** Gives each of the people {@code grade} from {@code from} (the sheet's today when left out), in
     * list order (the order they were added); those not on the sheet, or saved by someone else while
     * this runs, are left as they are. The people it changed. */
    public List<Person> grade(String sheetId, String user, Collection<Long> personIds, String grade, LocalDate from) {
        Set<Long> asked = Set.copyOf(personIds);
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> asked.contains(p.getId()))
                .toList();
        List<Person> changed = new ArrayList<>();
        for (Person person : listed) {
            try {
                changed.add(service.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        grade, from, person.getTerms()));
            } catch (PersonStaleException | PersonNotFoundException | ObjectOptimisticLockingFailureException e) {
                // Left as it is.
            }
        }
        return changed;
    }
}
