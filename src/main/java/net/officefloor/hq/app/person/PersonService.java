package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people on a sheet: listed in the order they were added, added and edited one at a time. A
 * name already used by another person on the sheet (ignoring case and surrounding spaces) is
 * refused.
 */
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

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        requireUnused(sheetId, name, null);
        Person person = saveUnique(new Person(sheetId, name, grade));
        record("PERSON_ADDED", sheetId, user, person);
        return person;
    }

    @Transactional
    public Person edit(String sheetId, String user, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        requireUnused(sheetId, name, id);
        person.setName(name);
        person.setGrade(grade);
        Person saved = saveUnique(person);
        record("PERSON_EDITED", sheetId, user, saved);
        return saved;
    }

    private void record(String event, String sheetId, String user, Person person) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    private void requireUnused(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> { throw new DuplicatePersonNameException(); });
    }

    /** Writes the person now, so a name taken meanwhile by another user is refused by the store. */
    private Person saveUnique(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePersonNameException();
        }
    }
}
