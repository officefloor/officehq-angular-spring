package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;

    public PersonService(PersonRepository people, SheetRepository sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String name, String grade) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (people.existsBySheetIdAndNameKey(sheetId, Person.key(name))) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        return save(new Person(sheetId, name, grade));
    }

    @Transactional
    public Person update(String sheetId, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.key(name), id)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        person.setName(name);
        person.setGrade(grade);
        return save(person);
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            // Another user took the name at the same moment: the unique index refused it.
            throw new PersonNameTakenException(person.getSheetId(), person.getName());
        }
    }
}
