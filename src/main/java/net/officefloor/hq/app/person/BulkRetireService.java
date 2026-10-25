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

/** Retires selected people together. Each is retired on its own, as the row's retire would, so one that
 *  cannot be retired is left as it is and the others are still retired. */
@Service
public class BulkRetireService {

    private final PersonService service;
    private final PersonRepository people;
    private final SettingService settings;
    private final SheetRepository sheets;

    public BulkRetireService(PersonService service, PersonRepository people, SettingService settings,
            SheetRepository sheets) {
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Retire the people of these ids from the date (none: the sheet's today), in list order. An id not of
     *  the sheet is left out. The people retired, in the order they were retired. A viewer retires no one,
     *  nor does anyone on an archived sheet. */
    public List<Person> retire(String sheetId, String user, Collection<Long> ids, LocalDate from) {
        if (settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId)) return List.of();
        Set<Long> selected = new LinkedHashSet<>(ids);
        List<Person> retired = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            if (!selected.contains(person.getId())) continue;
            try {
                retired.add(service.retire(sheetId, user, person.getId(), from));
            } catch (ResponseStatusException notRetired) {
                // Left as it is.
            }
        }
        return retired;
    }
}
