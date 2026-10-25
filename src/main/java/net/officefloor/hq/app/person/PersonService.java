package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;

    public PersonService(PersonRepository people, SheetService sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String name, Grade grade) {
        sheets.load(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.keyOf(name))) {
            throw nameInUse();
        }
        return save(new Person(sheetId, name, grade));
    }

    /** Change a person's name and grade. */
    @Transactional
    public Person update(String sheetId, long id, String name, Grade grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.keyOf(name), id)) {
            throw nameInUse();
        }
        person.setName(name);
        person.setGrade(grade);
        return save(person);
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException raced) {
            // Another user took the same name at the same moment.
            throw nameInUse();
        }
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on the sheet.");
    }
}
