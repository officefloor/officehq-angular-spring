package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Holding the selected people together, from a date (null: the sheet's today) to a date (null: no end).
 * Each is held on its own, as the row's hold would, in list order: the people by their position. A
 * person who cannot be held (or is not on the sheet) is left as they are, writing nothing; the others
 * are still held. For a viewer, nobody is held.
 */
@Service
public class BulkHoldService {

    private final PersonService service;
    private final PersonRepository people;
    private final RoleService roles;

    public BulkHoldService(PersonService service, PersonRepository people, RoleService roles) {
        this.service = service;
        this.people = people;
        this.roles = roles;
    }

    /** Hold the selected people over the dates: the people held, in the order they were held. */
    public List<Person> hold(String sheetId, String user, Collection<Long> personIds, LocalDate from, LocalDate to) {
        Set<Long> selected = new HashSet<>(personIds);
        List<Person> held = new ArrayList<>();
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return held;
        }
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (!selected.contains(person.getId())) {
                continue;
            }
            try {
                held.add(service.hold(sheetId, user, person.getId(), from, to));
            } catch (ResponseStatusException refused) {
                // left as they are: their hold wrote nothing
            }
        }
        return held;
    }
}
