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
 * Retires a selection of people together from a date, in list order (the order they were added). Each person is
 * retired as the row's retire would retire them, so each records its own PERSON_RETIRED and rollup lines. One that
 * cannot be retired refuses the whole change: nothing is saved. A viewer changes nothing: none is retired.
 */
@Service
public class BulkRetireService {

    private final PersonRepository people;
    private final PersonService service;
    private final RoleService roles;

    public BulkRetireService(PersonRepository people, PersonService service, RoleService roles) {
        this.people = people;
        this.service = service;
        this.roles = roles;
    }

    /** How many people were retired. */
    public record Result(int retired) {
    }

    /** Retire each selected person from the date (null: the sheet's today). */
    @Transactional
    public Result retire(String sheetId, String user, Collection<Long> ids, LocalDate from) {
        if (roles.isViewer(sheetId, user)) {
            return new Result(0);
        }
        Set<Long> selected = new HashSet<>(ids);
        List<Person> ordered = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
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
            service.retire(sheetId, user, person.getId(), from);
        }
        return new Result(ordered.size());
    }
}
