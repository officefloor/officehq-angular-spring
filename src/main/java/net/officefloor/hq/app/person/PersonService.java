package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
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
    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (people.existsBySheetIdAndNameKey(sheetId, Person.key(name))) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        Person saved = save(new Person(sheetId, name, grade));
        audit.recordOnCommit(record("PERSON_ADDED", user, saved));
        return saved;
    }

    @Transactional
    public Person update(String sheetId, String user, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.key(name), id)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        person.setName(name);
        person.setGrade(grade);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", user, saved));
        return saved;
    }

    private static String record(String event, String user, Person person) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade();
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
