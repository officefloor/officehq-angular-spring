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
 * Holds a selection of people together over dates, in list order (the order they were added). Each person is held as
 * the row's hold would hold them, so each records its own PERSON_HELD line. One that cannot be held refuses the whole
 * change: nothing is saved. A viewer changes nothing: none is held.
 */
@Service
public class BulkHoldService {

    private final PersonRepository people;
    private final PersonService service;
    private final RoleService roles;

    public BulkHoldService(PersonRepository people, PersonService service, RoleService roles) {
        this.people = people;
        this.service = service;
        this.roles = roles;
    }

    /** How many people were held. */
    public record Result(int held) {
    }

    /** Hold each selected person from the date (null: the sheet's today) to the date (null: it does not end). */
    @Transactional
    public Result hold(String sheetId, String user, Collection<Long> ids, LocalDate from, LocalDate to) {
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
            service.hold(sheetId, user, person.getId(), from, to);
        }
        return new Result(ordered.size());
    }
}
