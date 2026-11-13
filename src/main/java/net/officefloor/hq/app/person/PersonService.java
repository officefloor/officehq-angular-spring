package net.officefloor.hq.app.person;

import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;

    public PersonService(PersonRepository people, SheetService sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String name, String grade) {
        sheets.load(sheetId);
        return people.save(new Person(sheetId, name, grade));
    }

    /** Change a person's name and grade. */
    @Transactional
    public Person update(String sheetId, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        person.setName(name);
        person.setGrade(grade);
        return people.save(person);
    }
}
