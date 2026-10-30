package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people on a sheet: listed in the order they were added, added and edited one at a time;
 * an edit made from a version someone else has saved since is refused. A person can be retired from a
 * date; no claim dated after it can then be added for them. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetService sheets,
            SheetRepository sheetRows, Rollup rollup, ApplicationEventPublisher events) {
        this.patrons = patrons;
        this.sheetRows = sheetRows;
        this.rollup = rollup;
        this.people = people;
        this.sheets = sheets;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Person person = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        events.publishEvent(new PersonChanged("PERSON_ADDED", sheetId, user, trimmed, grade));
        rollup.changed(sheetId, user, before);
        return person;
    }

    @Transactional
    public Person edit(String sheetId, String user, long personId, Long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version != null && version != person.getVersion()) {
            throw edited();
        }
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, personId)) {
            throw nameInUse();
        }
        Rollup.Figures before = rollup.figures(sheetId);
        person.setName(trimmed);
        person.setGrade(grade);
        people.flush();
        events.publishEvent(new PersonChanged("PERSON_EDITED", sheetId, user, trimmed, grade));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * Retire the person from a date; a date left out takes the sheet's today. The sheet stays locked
     * until the change commits, so a claim added meanwhile is checked against the new date.
     */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(on);
        people.flush();
        events.publishEvent(new PersonRetired(sheetId, user, person.getName(), on));
        return person;
    }

    /**
     * Put the person on hold or take them off it, moving the figures their OPEN claims count in. Asking
     * for the state they are already in changes nothing. Takes turns with claim changes on the sheet.
     */
    @Transactional
    public Person hold(String sheetId, String user, long personId, boolean held) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (person.isHeld() == held) {
            return person;
        }
        Rollup.Figures before = rollup.figures(sheetId);
        person.setHeld(held);
        people.flush();
        events.publishEvent(new PersonHeld(held ? "PERSON_HELD" : "PERSON_RELEASED", sheetId, user,
                person.getName()));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /** The ids of each person's patrons on the sheet, by person id, in the order they were named. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronsBySheet(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream().collect(Collectors.groupingBy(
                Patron::getPersonId, Collectors.mapping(Patron::getPatronId, Collectors.toList())));
    }

    /** The ids of the person's patrons, in the order they were named. */
    @Transactional(readOnly = true)
    public List<Long> patrons(String sheetId, long personId) {
        return patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream()
                .map(Patron::getPatronId).toList();
    }

    /**
     * The person names another person on the sheet as their patron. Naming themselves, someone not on
     * the sheet or someone already their patron is refused. Takes turns with other changes on the sheet.
     */
    @Transactional
    public Person addPatron(String sheetId, String user, long personId, long patronId) {
        sheetRows.lockById(sheetId);
        Person person = get(sheetId, personId);
        if (patronId == personId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron");
        }
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No such person"));
        if (patrons.existsByPersonIdAndPatronId(personId, patronId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a patron");
        }
        patrons.saveAndFlush(new Patron(sheetId, personId, patronId));
        events.publishEvent(new PatronAdded(sheetId, user, person.getName(), patron.getName()));
        return person;
    }

    /** Someone else saved the person after the editor loaded them. */
    public static ResponseStatusException edited() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Saved by someone else since loaded");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
