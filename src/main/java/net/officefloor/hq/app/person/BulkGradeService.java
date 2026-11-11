package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a selection of people a grade together, in list order (the order they were added). Each person is saved as the
 * person form would save them, keeping their name and terms, so each records its own PERSON_EDITED and rollup lines.
 * One that cannot be saved refuses the whole change: nothing is saved. A viewer changes nothing: none is given it.
 */
@Service
public class BulkGradeService {

    private final PersonRepository people;
    private final PersonService service;
    private final RoleService roles;

    public BulkGradeService(PersonRepository people, PersonService service, RoleService roles) {
        this.people = people;
        this.service = service;
        this.roles = roles;
    }

    /** How many people were given the grade. */
    public record Result(int changed) {
    }

    /** Give each selected person the grade from the from-date (null: the sheet's today). */
    @Transactional
    public Result grade(String sheetId, String user, Collection<Long> ids, String grade, LocalDate from) {
        if (roles.isViewer(sheetId, user)) {
            return new Result(0);
        }
        Set<Long> selected = new HashSet<>(ids);
        List<Person> ordered = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> selected.contains(p.getId()))
                .toList();
        if (ordered.size() != selected.size()) {
            // A selected id not on the sheet.
            Long missing = selected.stream()
                    .filter(id -> ordered.stream().noneMatch(p -> p.getId().equals(id)))
                    .findFirst().orElseThrow();
            throw new PersonNotFoundException(sheetId, missing);
        }
        for (Person person : ordered) {
            service.update(sheetId, user, person.getId(), null, person.getName(), grade, from, person.getTerms(),
                    person.getContact());
        }
        return new Result(ordered.size());
    }
}
