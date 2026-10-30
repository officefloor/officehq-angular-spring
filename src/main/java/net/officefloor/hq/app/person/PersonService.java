package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people on a sheet: listed in the order they were added, added and edited one at a time. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final ApplicationEventPublisher events;

    public PersonService(PersonRepository people, SheetService sheets, ApplicationEventPublisher events) {
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
        Person person = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        events.publishEvent(new PersonChanged("PERSON_ADDED", sheetId, user, trimmed, grade));
        return person;
    }

    @Transactional
    public Person edit(String sheetId, String user, long personId, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, personId)) {
            throw nameInUse();
        }
        person.setName(trimmed);
        person.setGrade(grade);
        people.flush();
        events.publishEvent(new PersonChanged("PERSON_EDITED", sheetId, user, trimmed, grade));
        return person;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
