package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people on each sheet. Names arrive validated (not blank) and are stored trimmed; a name
 * is unique on the sheet, compared without regard to case.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;

    public PersonService(PersonRepository people, SheetRepository sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        requireSheet(sheetId);
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String name, String grade) {
        requireSheet(sheetId);
        requireFreeName(sheetId, name.trim(), null);
        return saved(new Person(sheetId, name.trim(), grade));
    }

    @Transactional
    public Person update(String sheetId, long personId, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        requireFreeName(sheetId, name.trim(), person.getId());
        person.setName(name.trim());
        person.setGrade(grade);
        return saved(person);
    }

    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameTaken(sheetId, name, exceptId)) {
            throw new PersonNameTakenException();
        }
    }

    /** Save now, so a name another user took at the same moment is refused here, not at commit. */
    private Person saved(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new PersonNameTakenException();
        }
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
