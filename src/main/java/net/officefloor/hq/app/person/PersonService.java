package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on each sheet. */
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

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        requireFree(sheetId, name, null);
        Person person = people.saveAndFlush(new Person(sheetId, name, grade));
        audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        return person;
    }

    @Transactional
    public Person update(String sheetId, String user, long personId, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        requireFree(sheetId, name, person.getId());
        person.setName(name);
        person.setGrade(grade);
        people.flush();
        audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        return person;
    }

    /** Refuses a name another person on the sheet (other than {@code self}) already has. */
    private void requireFree(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new PersonNameTakenException();
                });
    }
}
