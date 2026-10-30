package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Person added = new Person(sheetId, trimmed, grade);
        added.setTerms(terms);
        Person person = people.saveAndFlush(added);
        events.publishEvent(new PersonChanged("PERSON_ADDED", sheetId, user, trimmed, grade,
                terms == null ? null : terms.toString()));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * Save the person's name and record the grade from a date (none: the sheet's today), keeping the
     * grades already recorded; a grade left as the row showed it (in force on the sheet's today) with no
     * date records nothing. The person's own terms are set to those given (null: none). The edit names the
     * grade in force on the sheet's today after it and, when they changed, the terms.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
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
        LocalDate today = sheets.today(sheetId);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.gradeFrom(grade, gradeFrom != null ? gradeFrom : today);
        }
        String termsChange = Objects.equals(terms, person.getTerms()) ? null : terms == null ? "" : terms.toString();
        person.setTerms(terms);
        people.flush();
        events.publishEvent(new PersonChanged("PERSON_EDITED", sheetId, user, trimmed, person.gradeOn(today),
                termsChange));
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
     * Add a hold on the person from a date (none: the sheet's today) to an optional date, keeping their
     * other holds and moving the figures their OPEN claims dated within it count in. Refused when the
     * to-date is before the from-date. Takes turns with claim changes on the sheet.
     */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        person.hold(on, to);
        people.flush();
        events.publishEvent(new PersonHeld(sheetId, user, person.getName(), on));
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
