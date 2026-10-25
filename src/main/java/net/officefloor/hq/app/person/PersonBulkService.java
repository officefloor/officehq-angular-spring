package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** A grade given to, or a retirement of, several people at once. Each person is saved in their own transaction, through
 * {@link PersonService#update}, exactly as the person form saves them (keeping their name and terms),
 * so each records its own PERSON_EDITED and rollup lines; a bulk retire retires each through
 * {@link PersonService#retire}, exactly as the row's retire does, recording its PERSON_RETIRED and rollup
 * lines; a bulk hold holds each through {@link PersonService#hold}, exactly as the row's hold does,
 * recording its PERSON_HELD and rollup lines. A viewer's bulk change, or one on an archived sheet, goes through with nothing done: no one is
 * changed. */
@Service
public class PersonBulkService {

    private final PersonService service;
    private final PersonRepository people;
    private final SettingsService settings;
    private final SheetRepository sheets;

    public PersonBulkService(PersonService service, PersonRepository people, SettingsService settings,
            SheetRepository sheets) {
        this.service = service;
        this.people = people;
        this.settings = settings;
        this.sheets = sheets;
    }

    /** Gives each of the people {@code grade} from {@code from} (the sheet's today when left out), in
     * list order; those not on the sheet, or saved by someone else while
     * this runs, are left as they are. The people it changed. */
    public List<Person> grade(String sheetId, String user, Collection<Long> personIds, String grade, LocalDate from) {
        Set<Long> asked = Set.copyOf(personIds);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        if (sheets.findById(sheetId).map(Sheet::isArchived).orElse(false)) return List.of();
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> asked.contains(p.getId()))
                .toList();
        List<Person> changed = new ArrayList<>();
        for (Person person : listed) {
            try {
                changed.add(service.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        grade, from, person.getTerms(), person.getContact()));
            } catch (PersonStaleException | PersonNotFoundException | ObjectOptimisticLockingFailureException e) {
                // Left as it is.
            }
        }
        return changed;
    }

    /** Retires each of the people from {@code from} (the sheet's today when left out), in list order;
     * those not on the sheet are left out. The people it retired. */
    public List<Person> retire(String sheetId, String user, Collection<Long> personIds, LocalDate from) {
        Set<Long> asked = Set.copyOf(personIds);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        if (sheets.findById(sheetId).map(Sheet::isArchived).orElse(false)) return List.of();
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> asked.contains(p.getId()))
                .toList();
        List<Person> changed = new ArrayList<>();
        for (Person person : listed) {
            try {
                changed.add(service.retire(sheetId, user, person.getId(), from));
            } catch (PersonNotFoundException | ObjectOptimisticLockingFailureException e) {
                // Left as it is.
            }
        }
        return changed;
    }

    /** Puts each of the people on a hold from {@code from} (the sheet's today when left out) to
     * {@code to} (no end when left out), in list order; those not on the sheet, or whose hold is
     * refused, are left out. The people it held. */
    public List<Person> hold(String sheetId, String user, Collection<Long> personIds, LocalDate from, LocalDate to) {
        Set<Long> asked = Set.copyOf(personIds);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        if (sheets.findById(sheetId).map(Sheet::isArchived).orElse(false)) return List.of();
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> asked.contains(p.getId()))
                .toList();
        List<Person> changed = new ArrayList<>();
        for (Person person : listed) {
            try {
                changed.add(service.hold(sheetId, user, person.getId(), from, to));
            } catch (PersonHoldException | PersonNotFoundException | ObjectOptimisticLockingFailureException e) {
                // Left as it is.
            }
        }
        return changed;
    }
}
