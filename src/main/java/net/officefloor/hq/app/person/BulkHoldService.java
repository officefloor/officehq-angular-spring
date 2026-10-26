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

/** Holds selected people together. Each is held on its own, as the row's hold would, so one that cannot
 *  be held is left as it is and the others are still held. */
@Service
public class BulkHoldService {

    private final PersonService service;
    private final PersonRepository people;
    private final SettingService settings;
    private final SheetRepository sheets;

    public BulkHoldService(PersonService service, PersonRepository people, SettingService settings,
            SheetRepository sheets) {
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Hold the people of these ids from the from-date (none: the sheet's today) to the to-date (none: no
     *  end), in list order. An id not of the sheet is left out. The people held, in the order they were
     *  held. A viewer holds no one, nor does anyone on an archived sheet. */
    public List<Person> hold(String sheetId, String user, Collection<Long> ids, LocalDate from, LocalDate to) {
        if (settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId)) return List.of();
        Set<Long> selected = new LinkedHashSet<>(ids);
        List<Person> held = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            if (!selected.contains(person.getId())) continue;
            try {
                held.add(service.hold(sheetId, user, person.getId(), from, to));
            } catch (ResponseStatusException notHeld) {
                // Left as it is.
            }
        }
        return held;
    }
}
