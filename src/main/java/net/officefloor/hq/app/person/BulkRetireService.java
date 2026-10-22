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
 * Retiring the selected people together, from a date (null: the sheet's today). Each is retired on its
 * own, as the row's retire would, in list order: the people by their position. A person who cannot be
 * retired (or is not on the sheet) is left as they are, writing nothing; the others are still retired.
 * For a viewer, nobody is retired.
 */
@Service
public class BulkRetireService {

    private final PersonService service;
    private final PersonRepository people;
    private final RoleService roles;

    public BulkRetireService(PersonService service, PersonRepository people, RoleService roles) {
        this.service = service;
        this.people = people;
        this.roles = roles;
    }

    /** Retire the selected people from the date: the people retired, in the order they were retired. */
    public List<Person> retire(String sheetId, String user, Collection<Long> personIds, LocalDate from) {
        Set<Long> selected = new HashSet<>(personIds);
        List<Person> retired = new ArrayList<>();
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return retired;
        }
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (!selected.contains(person.getId())) {
                continue;
            }
            try {
                retired.add(service.retire(sheetId, user, person.getId(), from));
            } catch (ResponseStatusException refused) {
                // left as they are: their retire wrote nothing
            }
        }
        return retired;
    }
}
